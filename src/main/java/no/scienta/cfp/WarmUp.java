package no.scienta.cfp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Warms the JVM up before port 8080 opens. Each deploy starts a fresh instance, and the platform sends it traffic as
 * soon as the port answers; the harness's first tick then hit a cold JVM and timed out (POST /speakers took 3-5 s
 * against ~1 s warm). So, while Tomcat is up but has no public connector yet:
 *
 * <ol>
 *   <li>fill the connection pool (a TLS handshake each on Scaleway);</li>
 *   <li>send real HTTP requests through a temporary loopback-only connector: reads, and requests that are rejected
 *       before they write (400, 404, 409). Nothing is written to the database.</li>
 * </ol>
 *
 * Measured locally at --cpus=0.25 (starts in ~35 s, like production): the first burst of 30 POST /speakers took
 * p50 2.8 s / max 3.1 s cold, 0.8 / 1.3 s after this warm-up, 0.4 / 0.6 s on a warm instance. The web layer
 * (JSON, validation, error rendering) is most of the cold cost; running the write paths in-process as well didn't
 * add enough to keep (Boot 2.7 runs: 5.6 s cold, 3.7 s with in-process writes only, 1.3 s with HTTP).
 *
 * It never stops start-up: every step catches its errors, and it gives up after {@code cfp.warmup.max-seconds}.
 */
@Component
public class WarmUp implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    /**
     * Spring Boot 4 opens the port in WebServerStartStopLifecycle (package-private), whose phase is
     * WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024. Run just before it. (Boot 2.7 used
     * Integer.MAX_VALUE - 1; WarmUpTest.finishesBeforeThePortOpens fails if this ever runs after the port opens.)
     */
    static final int PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024 - 1;

    /** Summary of a warm-up run, for the log line and the tests. */
    public static final class Result {
        public int poolConnections;
        public int httpRequests;
        public int failures;
        public final Map<Integer, Integer> statuses = new TreeMap<>();
        public long millis;

        @Override
        public String toString() {
            return "pool " + poolConnections + " connections, " + httpRequests + " HTTP requests " + statuses + ", " + failures + " failures, " + millis + " ms";
        }
    }

    private final ApplicationContext context;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final boolean enabled;
    private final int rounds;
    private final long maxMillis;

    private volatile boolean running;
    private volatile Result result;

    public WarmUp(ApplicationContext context, DataSource dataSource, JdbcTemplate jdbc,
                  JsonMapper json,
                  @Value("${cfp.warmup.enabled:true}") boolean enabled,
                  @Value("${cfp.warmup.rounds:5}") int rounds,
                  @Value("${cfp.warmup.max-seconds:15}") int maxSeconds) {
        this.context = context;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.json = json;
        this.enabled = enabled;
        this.rounds = rounds;
        this.maxMillis = TimeUnit.SECONDS.toMillis(maxSeconds);
    }

    /** The last run's summary, or null if it hasn't finished (or is disabled). */
    public Result result() {
        return result;
    }

    @Override
    public void start() {
        running = true;
        if (!enabled) {
            return;
        }
        Result r = new Result();
        long start = System.currentTimeMillis();
        long deadline = start + maxMillis;
        try {
            fillPool(r);
            http(r, deadline);
        } catch (Throwable e) {
            r.failures++;
            log.warn("warm-up stopped early: " + e);
        }
        r.millis = System.currentTimeMillis() - start;
        log.info("warm-up done: " + r);
        result = r;
    }

    // ---------------------------------------------------------------- 1. the pool

    private void fillPool(Result r) {
        int size = dataSource instanceof HikariDataSource ? ((HikariDataSource) dataSource).getMaximumPoolSize() : 5;
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                held.add(dataSource.getConnection());
            }
        } catch (Exception e) {
            r.failures++;
            log.warn("warm-up: could only open " + held.size() + " connections: " + e);
        } finally {
            r.poolConnections = held.size();
            for (Connection c : held) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // back to the pool or gone, either way fine
                }
            }
        }
    }

    // ---------------------------------------------------------------- 2. HTTP on a loopback-only connector

    private void http(Result r, long deadline) throws Exception {
        Tomcat tomcat = tomcat();
        if (tomcat == null) {
            return; // not running in an embedded Tomcat (e.g. a mock web environment)
        }
        Connector connector = new Connector("HTTP/1.1");
        connector.setPort(0);
        connector.setProperty("address", "127.0.0.1");
        tomcat.getService().addConnector(connector); // starts it: the service is already running
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            String base = "http://127.0.0.1:" + connector.getLocalPort();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5)).executor(pool).build();
            List<HttpRequest> requests = requests(base);
            for (int i = 0; i < rounds && System.currentTimeMillis() < deadline; i++) {
                List<Future<HttpResponse<Void>>> sent = new ArrayList<>();
                for (HttpRequest req : requests) {
                    sent.add(client.sendAsync(req, HttpResponse.BodyHandlers.discarding()));
                }
                for (Future<HttpResponse<Void>> f : sent) {
                    try {
                        int status = f.get(10, TimeUnit.SECONDS).statusCode();
                        r.statuses.merge(status, 1, Integer::sum);
                        r.httpRequests++;
                    } catch (Exception e) {
                        r.failures++;
                    }
                }
            }
        } finally {
            tomcat.getService().removeConnector(connector); // stops it
            connector.destroy();
            pool.shutdownNow();
        }
    }

    private Tomcat tomcat() {
        if (!(context instanceof ServletWebServerApplicationContext)) {
            return null;
        }
        WebServer server = ((ServletWebServerApplicationContext) context).getWebServer();
        return server instanceof TomcatWebServer ? ((TomcatWebServer) server).getTomcat() : null;
    }

    /** Requests that read, or are rejected before any write. None of them changes data. */
    private List<HttpRequest> requests(String base) {
        Map<String, Object> existing = existing();
        List<HttpRequest> list = new ArrayList<>();
        list.add(get(base + "/health"));
        list.add(get(base + "/talks/0"));
        list.add(get(base + "/speakers/0"));
        list.add(get(base + "/speakers/0/talks"));
        list.add(get(base + "/talks?speakerId=0"));
        list.add(get(base + "/talks?sort=nope"));
        list.add(send(base + "/speakers", "POST", "{\"name\":\" \",\"email\":\"not-an-email\"}"));
        list.add(send(base + "/speakers", "POST", "{\"name\":\"x\""));
        list.add(send(base + "/talks", "POST", "{\"title\":\"t\",\"format\":\"KEYNOTE\",\"level\":\"BEGINNER\",\"speakerId\":1}"));
        list.add(send(base + "/talks", "POST", "{\"title\":\"t\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":0}"));
        list.add(send(base + "/talks/0/ratings/1", "PUT", "{\"score\":3}"));
        list.add(send(base + "/talks/0/ratings/1", "PUT", "{\"score\":9}"));
        list.add(send(base + "/talks/0/status", "PUT", "{\"status\":\"ACCEPTED\"}"));
        list.add(send(base + "/talks/0/withdraw", "POST", "{\"speakerId\":1}"));
        list.add(send(base + "/talks/0/co-speakers/1", "PUT", null));
        list.add(send(base + "/talks/0/co-speakers/1", "DELETE", null));
        list.add(send(base + "/workshops/0/registrations", "POST", "{\"name\":\"Warm Up\",\"email\":\"warmup@warmup.invalid\"}"));
        list.add(send(base + "/workshops/0/registrations", "POST", "{\"name\":1,\"email\":\"nope\"}"));
        list.add(get(base + "/workshops/0/registrations"));
        if (existing.containsKey("speaker")) {
            Object speaker = existing.get("speaker");
            list.add(get(base + "/speakers/" + speaker));
            list.add(get(base + "/speakers/" + speaker + "/talks"));
            list.add(get(base + "/talks?speakerId=" + speaker));
            // A taken e-mail: validated, then 409 before the insert.
            list.add(send(base + "/speakers", "POST",
                    "{\"name\":\"Warm Up\",\"email\":" + quote(existing.get("email")) + "}"));
        }
        if (existing.containsKey("talk")) {
            list.add(get(base + "/talks/" + existing.get("talk")));
        }
        return list;
    }

    private Map<String, Object> existing() {
        Map<String, Object> found = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> speakers = jdbc.queryForList("SELECT id, email FROM speaker ORDER BY id DESC LIMIT 1");
            if (!speakers.isEmpty()) {
                found.put("speaker", speakers.get(0).get("id"));
                found.put("email", speakers.get(0).get("email"));
            }
            List<Long> talks = jdbc.queryForList("SELECT id FROM talk ORDER BY id DESC LIMIT 1", Long.class);
            if (!talks.isEmpty()) {
                found.put("talk", talks.get(0));
            }
        } catch (Exception e) {
            log.warn("warm-up: couldn't read existing ids: " + e);
        }
        return found;
    }

    private String quote(Object s) {
        return json.writeValueAsString(String.valueOf(s));
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private static HttpRequest send(String url, String method, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (body == null) {
            return b.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
