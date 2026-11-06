package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Lists of talks are read in a fixed number of queries, however many talks there are. Every talk used to cost two
 * queries of its own (co-speakers, rating stats), plus a speaker lookup in /admin/stats and the CSV: GET /talks took
 * 18-34 s and /admin/stats 23-40 s in production at ~3600 talks. The statements are counted on the app's own
 * DataSource, so the test fails on the old code whatever the machine's speed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ListQueriesTest.CountingDataSource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListQueriesTest {

    private static final int TALKS = 30;
    private static final int MAX_STATEMENTS = 5;
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    static final AtomicInteger STATEMENTS = new AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> talks = new ArrayList<>();
    private String speaker;

    @BeforeAll
    void someTalks() throws Exception {
        String coSpeaker = id(send("POST", "/speakers", speakerJson("Co")));
        for (int i = 0; i < TALKS / 3; i++) {
            speaker = id(send("POST", "/speakers", speakerJson("Main " + i)));
            for (int j = 0; j < 3; j++) {
                String talk = id(send("POST", "/talks", "{\"title\":\"T" + i + "-" + j
                        + "\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
                talks.add(talk);
                send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":" + (1 + j) + "}");
                send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":5}");
                send("PUT", "/talks/" + talk + "/co-speakers/" + coSpeaker, null);
            }
        }
    }

    @Test
    void listingAllTalksTakesAFixedNumberOfQueries() throws Exception {
        HttpResponse<String> res = counted("/talks");
        assertEquals(TALKS, count(res.body()), res.body());
        assertTrue(res.body().contains("\"coSpeakerIds\":[" + firstCoSpeaker() + "]"), res.body());
        assertTrue(res.body().contains("\"averageRating\":3.0,\"ratingCount\":2"), res.body());
    }

    @Test
    void theRankingTakesAFixedNumberOfQueries() throws Exception {
        HttpResponse<String> res = counted("/talks?sort=rating");
        assertEquals(TALKS, count(res.body()));
        assertTrue(res.body().indexOf("\"averageRating\":4.0") < res.body().indexOf("\"averageRating\":3.0"), res.body());
    }

    @Test
    void aSpeakersTalksTakeAFixedNumberOfQueries() throws Exception {
        assertEquals(3, count(counted("/speakers/" + speaker + "/talks").body()));
        assertEquals(3, count(counted("/talks?speakerId=" + speaker).body()));
    }

    @Test
    void theStatsTakeAFixedNumberOfQueries() throws Exception {
        HttpResponse<String> res = counted("/admin/stats");
        assertTrue(res.body().contains("\"speakersWithTalks\":" + TALKS / 3 + ","), res.body());
        assertTrue(res.body().contains("\"talks\":" + TALKS + ","), res.body());
    }

    @Test
    void theCsvTakesAFixedNumberOfQueries() throws Exception {
        HttpResponse<String> res = counted("/admin/talks.csv");
        assertEquals(TALKS + 1, res.body().split("\n").length, res.body());
        assertTrue(res.body().contains(";Main 0;SUBMITTED;"), res.body());
    }

    private HttpResponse<String> counted(String path) throws Exception {
        STATEMENTS.set(0);
        HttpResponse<String> res = send("GET", path, null);
        int statements = STATEMENTS.get();
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(statements <= MAX_STATEMENTS, "GET " + path + " ran " + statements + " SQL statements for "
                + TALKS + " talks (at most " + MAX_STATEMENTS + ")");
        return res;
    }

    private String firstCoSpeaker() throws Exception {
        Matcher m = Pattern.compile("\"coSpeakerIds\":\\[(\\d+)]").matcher(send("GET", "/talks/" + talks.get(0), null).body());
        assertTrue(m.find());
        return m.group(1);
    }

    private static String speakerJson(String name) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + UUID.randomUUID() + "@example.com\"}";
    }

    private static String id(HttpResponse<String> res) {
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.statusCode() + " " + res.body());
        return m.group(1);
    }

    private static int count(String body) {
        Matcher m = ID.matcher(body);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Wraps the app's DataSource so every statement prepared or created on its connections is counted. */
    @TestConfiguration
    static class CountingDataSource {

        @Bean
        static BeanPostProcessor countStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource) || Proxy.isProxyClass(bean.getClass())) {
                        return bean;
                    }
                    DataSource target = (DataSource) bean;
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            forward(target, (method, result) -> result instanceof Connection
                                    ? Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                            new Class<?>[] {Connection.class}, forward(result, (m, r) -> {
                                                if (m.getName().equals("prepareStatement") || m.getName().equals("createStatement")
                                                        || m.getName().equals("prepareCall")) {
                                                    STATEMENTS.incrementAndGet();
                                                }
                                                return r;
                                            }))
                                    : result));
                }
            };
        }

        private static InvocationHandler forward(Object target, java.util.function.BiFunction<java.lang.reflect.Method, Object, Object> after) {
            return (proxy, method, args) -> {
                try {
                    return after.apply(method, method.invoke(target, args));
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
        }
    }
}
