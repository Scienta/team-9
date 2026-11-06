package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * After a deploy the harness's first tick hit a cold JVM and timed out (POST /speakers 3-5 s). The warm-up runs the
 * request paths before port 8080 opens, and must leave production data exactly as it found it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WarmUpTest {

    private static String database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        database = TestDatabase.fresh(registry);
        // Existing data, as in production, so the warm-up's reads find a real speaker and talk.
        String schema = new String(Files.readAllBytes(Paths.get("src/main/resources/schema.sql")), StandardCharsets.UTF_8);
        try (Connection c = TestDatabase.connect(database); Statement s = c.createStatement()) {
            s.execute(schema);
            s.execute("INSERT INTO speaker (name, email) VALUES ('Ada', 'ada@example.test'), ('Bob', 'bob@example.test')");
            s.execute("INSERT INTO talk (title, format, duration, level, speaker_id, submitted_at) "
                    + "VALUES ('Existing', 'PRESENTATION', 45, 'BEGINNER', 1, 0)");
            s.execute("INSERT INTO rating (talk_id, reviewer_id, score, rated_at) VALUES (1, 7, 3, '2026-11-06 12:00:00')");
        }
    }

    @TestConfiguration
    static class ServerStart {
        volatile boolean warmUpDoneWhenServerStarted;

        @Bean
        ServerStart serverStart() {
            return this;
        }

        @EventListener
        void started(WebServerInitializedEvent event) {
            warmUpDoneWhenServerStarted = event.getApplicationContext().getBean(WarmUp.class).result() != null;
        }
    }

    @Autowired
    WarmUp warmUp;

    @Autowired
    ServerStart serverStart;

    @Test
    void finishesBeforeThePortOpens() {
        assertTrue(serverStart.warmUpDoneWhenServerStarted, "the server started before the warm-up was done");
    }

    @Test
    void sendsHttpRequestsWithoutErrors() {
        WarmUp.Result r = warmUp.result();
        assertTrue(r.httpRequests > 0, "no HTTP requests ran: " + r);
        assertEquals(0, r.failures, "warm-up steps failed: " + r);
        r.statuses.keySet().forEach(s -> assertTrue(s >= 200 && s < 500, "warm-up got " + s + ": " + r));
        assertTrue(r.statuses.containsKey(200) && r.statuses.containsKey(400) && r.statuses.containsKey(404)
                && r.statuses.containsKey(409), "expected 200, 400, 404 and 409 among " + r.statuses);
    }

    @Test
    void leavesTheDataUntouched() throws Exception {
        try (Connection c = TestDatabase.connect(database); Statement s = c.createStatement()) {
            assertEquals(2, count(s, "SELECT COUNT(*) FROM speaker"));
            assertEquals(1, count(s, "SELECT COUNT(*) FROM talk"));
            assertEquals(1, count(s, "SELECT COUNT(*) FROM rating"));
            assertEquals(0, count(s, "SELECT COUNT(*) FROM talk_co_speaker"));
            assertEquals(0, count(s, "SELECT COUNT(*) FROM workshop_registration"));
            assertEquals(1, count(s, "SELECT COUNT(*) FROM talk WHERE status = 'SUBMITTED'"));
            assertEquals(1, count(s, "SELECT COUNT(*) FROM rating WHERE score = 3"));
        }
    }

    private static long count(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
