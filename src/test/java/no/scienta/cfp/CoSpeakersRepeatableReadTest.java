package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Co-speaker mutations on a database whose default isolation is REPEATABLE READ. Its own database (and so its
 * own Spring context), set up before the app connects, so no other test sees this default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoSpeakersRepeatableReadTest {

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
    void queuedAdditionsStillRespectCapacityUnderRepeatableReadDefault() throws Exception {
        assertEquals("repeatable read", jdbc.queryForObject("SHOW default_transaction_isolation", String.class));
        long talk = json.readTree(send("POST", "/talks", "{\"title\":\"Together\",\"format\":\"PRESENTATION\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speaker() + "}", 201)).get("id").asLong();
        List<Long> candidates = new ArrayList<>();
        for (int i = 0; i < 4; i++) candidates.add(speaker());

        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        try (Connection holder = TestDatabase.connect(database); Connection observer = TestDatabase.connect(database)) {
            CoSpeakerLocks.lockTalk(holder, talk);
            for (long co : candidates) {
                sent.add(http.sendAsync(request("PUT", "/talks/" + talk + "/co-speakers/" + co, null),
                        HttpResponse.BodyHandlers.ofString()));
            }
            // All four PUTs must be queued on the lock, so each took its view of membership after waiting.
            assertEquals(candidates.size(), CoSpeakerLocks.awaitTalkLockWaiters(observer, candidates.size()));
            holder.commit();
        }
        int accepted = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> response = f.get(10, TimeUnit.SECONDS);
            assertTrue(response.statusCode() == 200 || response.statusCode() == 409, response.body());
            if (response.statusCode() == 200) accepted++;
        }
        assertEquals(2, accepted);
        List<Long> rows = jdbc.queryForList("SELECT speaker_id FROM talk_co_speaker WHERE talk_id = ?", Long.class, talk);
        assertEquals(2, rows.size());
        assertTrue(candidates.containsAll(rows));
        // The explicit READ COMMITTED must not stick to pooled connections: they keep the database default.
        CoSpeakerLocks.assertPoolDefaults(dataSource, "repeatable read");
    }

    private long speaker() throws Exception {
        return json.readTree(send("POST", "/speakers", "{\"name\":\"Co Speaker\",\"email\":\"" + UUID.randomUUID()
                + "@example.com\"}", 201)).get("id").asLong();
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
