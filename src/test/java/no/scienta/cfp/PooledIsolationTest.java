package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Production's database answers like this (harness workshop-registration/check-4 stored 21 of 20 seats; scheduling
 * check-6 put one speaker twice in a slot): transactions start at REPEATABLE READ, and the JDBC driver's
 * {@code setTransactionIsolation} (a session-level SET) doesn't reach the transaction through the connection pooler in
 * front of it. So a lock-then-read sees the snapshot from before the lock wait. This test reproduces both: the
 * database's default is REPEATABLE READ, and the app's connections ignore {@code setTransactionIsolation}. Every
 * lock-then-check limit must still hold (lessons.md L18).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PooledIsolationTest {

    private static final int ROUNDS = 3;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String database = TestDatabase.fresh(registry);
        TestDatabase.execute("ALTER DATABASE " + database + " SET default_transaction_isolation = 'repeatable read'");
    }

    /** Calls to the driver's session-level {@code setTransactionIsolation}; the app should make none (review finding 1). */
    static final java.util.concurrent.atomic.AtomicInteger SESSION_ISOLATION_SETS = new java.util.concurrent.atomic.AtomicInteger();

    /** Wraps the app's DataSource so its connections drop {@code setTransactionIsolation}, as the pooler does. */
    @TestConfiguration
    static class DropSessionIsolation {
        @Bean
        static BeanPostProcessor dropSessionIsolation() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ? wrap((DataSource) bean) : bean;
                }
            };
        }

        private static DataSource wrap(DataSource target) {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> {
                        Object result = invoke(target, method, args);
                        return result instanceof Connection ? wrap((Connection) result) : result;
                    });
        }

        private static Connection wrap(Connection target) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("setTransactionIsolation")) {
                            SESSION_ISOLATION_SETS.incrementAndGet();
                            return null;
                        }
                        return invoke(target, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    DataSource dataSource;

    @Test
    void theDatabaseReallyDefaultsToRepeatableRead() {
        assertEquals("repeatable read", jdbc.queryForObject("SHOW default_transaction_isolation", String.class));
        // The simulation is in place: otherwise noSessionLevelIsolationSet would pass trivially (review finding E3).
        assertTrue(Proxy.isProxyClass(dataSource.getClass()), dataSource.getClass().getName());
    }

    // A session-level SET runs outside the transaction and, through the pooler, on someone else's backend (AGENTS.md):
    // the isolation is only ever set inside the transaction, also for methods pinned to READ COMMITTED or REPEATABLE READ.
    @Test
    void noSessionLevelIsolationSet() throws Exception {
        long workshop = accept(talk(speaker(), "WORKSHOP"));
        SESSION_ISOLATION_SETS.set(0);
        assertEquals(201, send("POST", "/workshops/" + workshop + "/registrations",           // READ_COMMITTED pin
                "{\"name\":\"Once\",\"email\":\"once-" + UUID.randomUUID() + "@example.com\"}").statusCode());
        assertEquals(200, send("GET", "/admin/stats", null).statusCode());                      // REPEATABLE_READ pin
        assertEquals(201, send("POST", "/talks", "{\"title\":\"T\",\"format\":\"PRESENTATION\","  // default
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speaker() + "}").statusCode());
        assertEquals(0, SESSION_ISOLATION_SETS.get());
    }

    // workshop-registration R4: 20 seats, also in a rush
    @Test
    void workshopRushNeverExceedsTwentySeats() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long workshop = accept(talk(speaker(), "WORKSHOP"));
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 23; i++) { // > 20, few enough that the queue clears well inside the 2 s lock_timeout
                sent.add(async("POST", "/workshops/" + workshop + "/registrations",
                        "{\"name\":\"Rush " + i + "\",\"email\":\"rush-" + UUID.randomUUID() + "@example.com\"}"));
            }
            assertEquals(20, count(sent, 201), "round " + round);
            assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM workshop_registration WHERE talk_id = ?",
                    Integer.class, workshop), "round " + round);
        }
    }

    // scheduling R7: nobody speaks in two rooms at once, also when the committee clicks at once
    @Test
    void schedulingRushNeverPutsOneSpeakerTwiceInASlot() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM programme");
            long speaker = speaker();
            List<Long> talks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                talks.add(accept(talk(speaker, "PRESENTATION")));
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            String[] rooms = {"R1", "R2", "R3"};
            for (int i = 0; i < 3; i++) {
                sent.add(async("PUT", "/programme/" + rooms[i] + "/S2", "{\"talkId\":" + talks.get(i) + "}"));
            }
            assertEquals(1, count(sent, 200), "round " + round);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM programme WHERE slot = 'S2'", Integer.class));
        }
    }

    // scheduling R5: one talk per room and slot; a loser must see the winner (409), not miss it
    @Test
    void schedulingRushForOnePlaceHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM programme");
            List<Long> talks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                talks.add(accept(talk(speaker(), "PRESENTATION")));
            }
            // All talks exist first, so the PUTs really arrive together (review finding 6).
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (long talk : talks) {
                sent.add(async("PUT", "/programme/R2/S3", "{\"talkId\":" + talk + "}"));
            }
            int ok = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.join();
                assertTrue(res.statusCode() == 200 || res.body().contains("R2 S3 already has a talk"),
                        "round " + round + ": " + res.statusCode() + " " + res.body());
                ok += res.statusCode() == 200 ? 1 : 0;
            }
            assertEquals(1, ok, "round " + round);
        }
    }

    // co-speakers: at most 2 per talk, also when they're added at once (workflow/co-speakers.md; review finding S1)
    @Test
    void coSpeakerRushNeverExceedsTwo() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long talk = talk(speaker(), "PRESENTATION");
            List<Long> candidates = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                candidates.add(speaker());
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (long candidate : candidates) {
                sent.add(async("PUT", "/talks/" + talk + "/co-speakers/" + candidate, null));
            }
            assertEquals(2, count(sent, 200), "round " + round);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM talk_co_speaker WHERE talk_id = ?", Integer.class, talk));
        }
    }

    // acceptance: two committee members deciding at once leave one decision, never a 500 (review finding S2)
    @Test
    void simultaneousDecisionsLeaveOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long talk = talk(speaker(), "PRESENTATION");
            for (int reviewer = 1; reviewer <= 2; reviewer++) {
                assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}").statusCode());
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (String status : new String[] {"ACCEPTED", "REJECTED", "ACCEPTED", "REJECTED"}) {
                sent.add(async("PUT", "/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}"));
            }
            assertEquals(1, count(sent, 200), "round " + round);
            String status = jdbc.queryForObject("SELECT status FROM talk WHERE id = ?", String.class, talk);
            assertTrue(status.equals("ACCEPTED") || status.equals("REJECTED"), status);
        }
    }

    // submission-cap-cr: a speaker's load (own talks and co-speaking) stays at 3 when a submission and a co-speaker
    // invitation arrive at once (review finding E1)
    @Test
    void submissionAndCoSpeakingRaceNeverExceedTheLoad() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long speaker = speaker();
            talk(speaker, "PRESENTATION");
            talk(speaker, "PRESENTATION");
            List<Long> others = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                others.add(talk(speaker(), "PRESENTATION"));
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                sent.add(async("POST", "/talks", "{\"title\":\"Mine " + i + "\",\"format\":\"PRESENTATION\","
                        + "\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
                sent.add(async("PUT", "/talks/" + others.get(i) + "/co-speakers/" + speaker, null));
            }
            int ok = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.join();
                assertTrue(res.statusCode() < 500, res.statusCode() + " " + res.body());
                ok += res.statusCode() < 300 ? 1 : 0;
            }
            assertEquals(1, ok, "round " + round);
            int load = jdbc.queryForObject("SELECT count(*) FROM talk WHERE speaker_id = ?", Integer.class, speaker)
                    + jdbc.queryForObject("SELECT count(*) FROM talk_co_speaker WHERE speaker_id = ?", Integer.class, speaker);
            assertEquals(3, load, "round " + round);
        }
    }

    // talks: at most 3 talks per speaker, also when they arrive at once
    @Test
    void submissionRushNeverExceedsTheCap() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long speaker = speaker();
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                sent.add(async("POST", "/talks", "{\"title\":\"Rush " + i + "\",\"format\":\"PRESENTATION\","
                        + "\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
            }
            assertEquals(3, count(sent, 201), "round " + round);
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM talk WHERE speaker_id = ?", Integer.class, speaker));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int count(List<CompletableFuture<HttpResponse<String>>> sent, int status) {
        int n = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() < 500, res.statusCode() + " " + res.body());
            n += res.statusCode() == status ? 1 : 0;
        }
        return n;
    }

    private long accept(long talk) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}").statusCode());
        }
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}");
        assertEquals(200, res.statusCode(), res.body());
        return talk;
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Pooled\",\"email\":\"pooled-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return id(res.body());
    }

    private long talk(long speaker, String format) throws Exception {
        HttpResponse<String> res = send("POST", "/talks", "{\"title\":\"T\",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return id(res.body());
    }

    private static long id(String json) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        assertTrue(m.find(), json);
        return Long.parseLong(m.group(1));
    }

    private CompletableFuture<HttpResponse<String>> async(String method, String path, String body) {
        return http.sendAsync(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
