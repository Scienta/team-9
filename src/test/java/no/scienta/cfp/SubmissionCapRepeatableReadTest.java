package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Submissions and co-speaker additions queued on one speaker's lock, on a database whose default isolation is
 * REPEATABLE READ. A snapshot taken before the lock wait would let every queued request see room under the cap
 * (workflow/specs/submission-cap-cr.md, "Concurrency and data").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionCapRepeatableReadTest {

    private static String database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.fresh(registry);
        TestDatabase.execute("ALTER DATABASE " + database + " SET default_transaction_isolation = 'repeatable read'");
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void queuedSubmitsAndInvitationsStillRespectTheCapUnderRepeatableReadDefault() throws Exception {
        assertEquals("repeatable read", jdbc.queryForObject("SHOW default_transaction_isolation", String.class));
        long s = speaker();
        List<Long> talks = new ArrayList<>();
        for (int i = 0; i < 2; i++) talks.add(talkBy(speaker()));

        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        try (Connection holder = TestDatabase.connect(database); Connection observer = TestDatabase.connect(database)) {
            holder.setAutoCommit(false);
            holder.createStatement().executeQuery("SELECT id FROM speaker WHERE id = " + s + " FOR UPDATE").close();
            for (int i = 0; i < 3; i++) {
                sent.add(http.sendAsync(request("POST", "/talks", talkBody(s)), HttpResponse.BodyHandlers.ofString()));
            }
            for (long talk : talks) {
                sent.add(http.sendAsync(request("PUT", "/talks/" + talk + "/co-speakers/" + s, null),
                        HttpResponse.BodyHandlers.ofString()));
            }
            // Every request must be queued on the speaker lock, so each counted the load after waiting.
            assertEquals(sent.size(), awaitSpeakerLockWaiters(observer, sent.size()));
            holder.commit();
        }
        int succeeded = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> response = f.get(10, TimeUnit.SECONDS);
            assertTrue(response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 409,
                    response.statusCode() + " " + response.body());
            if (response.statusCode() != 409) succeeded++;
        }
        assertEquals(3, succeeded);
        assertEquals(3, (int) jdbc.queryForObject("SELECT COUNT(*) FROM talk t WHERE t.speaker_id = ? "
                + "OR t.id IN (SELECT talk_id FROM talk_co_speaker WHERE speaker_id = ?)", Integer.class, s, s));
        // The explicit READ COMMITTED must not stick to pooled connections: they keep the database default.
        CoSpeakerLocks.assertPoolDefaults(dataSource, "repeatable read");
    }

    /** As {@link CoSpeakerLocks#awaitTalkLockWaiters}, for the speaker-row lock. */
    private static int awaitSpeakerLockWaiters(Connection observer, int expected) throws Exception {
        long deadline = System.nanoTime() + 1_500_000_000L;
        int waiting;
        do {
            try (Statement s = observer.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                         + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'"
                         + " AND query LIKE 'SELECT id FROM speaker WHERE id = % FOR UPDATE'")) {
                rs.next();
                waiting = rs.getInt(1);
            }
            if (waiting >= expected) {
                return waiting;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return waiting;
    }

    private long speaker() throws Exception {
        return json.readTree(send("POST", "/speakers", "{\"name\":\"Capped Speaker\",\"email\":\"" + UUID.randomUUID()
                + "@example.com\"}", 201)).get("id").asLong();
    }

    private long talkBy(long main) throws Exception {
        return json.readTree(send("POST", "/talks", talkBody(main), 201)).get("id").asLong();
    }

    private String talkBody(long main) {
        return "{\"title\":\"Capped\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + main + "}";
    }

    private String send(String method, String path, String body, int status) throws Exception {
        HttpResponse<String> response = http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + path + " " + response.body());
        return response.body();
    }

    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
