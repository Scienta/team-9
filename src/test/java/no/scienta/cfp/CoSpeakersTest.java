package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoSpeakersTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.fresh(registry);
    }

    private static String database;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    DataSource dataSource;

    @Autowired
    TalkDao talkDao;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void orderedMembershipIsPersistentIdempotentAndPresentOnEveryTalkRoute() throws Exception {
        long main = speaker(), first = speaker(), second = speaker(), third = speaker();
        JsonNode submitted = submit(main, 201);
        long talk = submitted.get("id").asLong();
        ids(submitted);
        // Add in reverse speaker-id order to prove this is addition order, not numeric order.
        JsonNode added = mutate("PUT", talk, second, 200);
        ids(added, second);
        assertEquals(main, added.get("speakerId").asLong());
        ids(mutate("PUT", talk, first, 200), second, first);
        ids(mutate("PUT", talk, second, 200), second, first);
        mutate("PUT", talk, third, 409);
        mutate("PUT", talk, main, 400);
        ids(get("/talks/" + talk), second, first);
        for (String path : Arrays.asList("/talks", "/talks?sort=rating", "/talks?speakerId=" + main,
                "/speakers/" + main + "/talks")) {
            JsonNode match = null;
            for (JsonNode item : get(path)) {
                if (item.get("id").asLong() == talk) match = item;
            }
            assertNotNull(match, path);
            ids(match, second, first);
        }
        // Removing someone who isn't a co-speaker of this talk is a 404 and changes nothing: a registered
        // speaker never added, the main speaker, and a co-speaker already removed (the harness's
        // co-speakers/check-1 sends that repeated DELETE; lessons.md).
        mutate("DELETE", talk, third, 404);
        ids(get("/talks/" + talk), second, first);
        mutate("DELETE", talk, main, 404);
        ids(get("/talks/" + talk), second, first);
        ids(mutate("DELETE", talk, second, 200), first);
        mutate("DELETE", talk, second, 404);
        ids(get("/talks/" + talk), first);
        ids(mutate("PUT", talk, second, 200), first, second);
        ids(mutate("DELETE", talk, first, 200), second);
        ids(mutate("DELETE", talk, second, 200));
        ids(get("/talks/" + talk));
        assertEquals(main, get("/talks/" + talk).get("speakerId").asLong());
    }

    @Test
    void coSpeakingDoesNotUseSubmissionQuotaOrAppearInOwnLists() throws Exception {
        long main = speaker(), co = speaker();
        // Co-speaking on more than three different talks must not consume any own submissions.
        for (int i = 0; i < 4; i++) {
            long otherMain = speaker();
            long otherTalk = submit(otherMain, 201).get("id").asLong();
            ids(mutate("PUT", otherTalk, co, 200), co);
        }
        assertEquals(0, get("/speakers/" + co + "/talks").size());
        assertEquals(0, get("/talks?speakerId=" + co).size());
        List<Long> own = new ArrayList<>();
        for (int i = 0; i < 3; i++) own.add(submit(co, 201).get("id").asLong());
        submit(co, 409);
        long talk = submit(main, 201).get("id").asLong();
        ids(mutate("PUT", talk, co, 200), co); // already at own quota
        for (String path : Arrays.asList("/speakers/" + co + "/talks", "/talks?speakerId=" + co)) {
            JsonNode list = get(path);
            assertEquals(3, list.size());
            for (JsonNode item : list) assertTrue(own.contains(item.get("id").asLong()));
        }
        submit(co, 409);
        // Main speaker's quota also still only counts their own talks.
        submit(main, 201);
        submit(main, 201);
        submit(main, 409);
    }

    @Test
    void invalidResourcesAndPathValuesFollowDocumentedStatusesWithoutMutations() throws Exception {
        long main = speaker(), co = speaker();
        long talk = submit(main, 201).get("id").asLong();
        for (String method : Arrays.asList("PUT", "DELETE")) {
            mutate(method, Long.MAX_VALUE, co, 404);
            mutate(method, talk, Long.MAX_VALUE, 404);
            mutate(method, talk, 0, 404);
            mutate(method, -1, co, 404);
            send(method, "/talks/not-an-id/co-speakers/" + co, null, 400);
            send(method, "/talks/" + talk + "/co-speakers/9223372036854775808", null, 400);
        }
        ids(get("/talks/" + talk));
        // No-body routes ignore a body rather than binding client-supplied membership.
        ids(send("PUT", path(talk, co), "{\"coSpeakerIds\":[]}", 200), co);
    }

    @Test
    void schemaCanBeReplayedWithoutLosingExistingTalksOrMemberships() throws Exception {
        long main = speaker(), co = speaker();
        long empty = submit(main, 201).get("id").asLong();
        long attached = submit(main, 201).get("id").asLong();
        ids(mutate("PUT", attached, co, 200), co);
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(dataSource);
        schema.execute(dataSource);
        ids(get("/talks/" + empty));
        ids(get("/talks/" + attached), co);
        assertEquals(main, get("/talks/" + attached).get("speakerId").asLong());
    }

    @Test
    void simultaneousDistinctAdditionsNeverExceedTwo() throws Exception {
        long talk = submit(speaker(), 201).get("id").asLong();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        List<Long> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) candidates.add(speaker());
        for (long co : candidates) sent.add(async("PUT", talk, co));
        int accepted = 0;
        for (CompletableFuture<HttpResponse<String>> future : sent) {
            HttpResponse<String> response = future.join();
            assertTrue(response.statusCode() == 200 || response.statusCode() == 409, response.body());
            if (response.statusCode() == 200) {
                accepted++;
                assertTrue(json.readTree(response.body()).get("coSpeakerIds").size() <= 2);
            }
        }
        assertEquals(2, accepted);
        JsonNode persisted = get("/talks/" + talk).get("coSpeakerIds");
        assertEquals(2, persisted.size());
        assertNotEquals(persisted.get(0), persisted.get(1));
        for (JsonNode id : persisted) assertTrue(candidates.contains(id.asLong()));
    }

    @Test
    void simultaneousDuplicateAndMixedMutationsRemainIdempotent() throws Exception {
        long talk = submit(speaker(), 201).get("id").asLong();
        long first = speaker(), second = speaker(), third = speaker();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) sent.add(async("PUT", talk, first));
        for (CompletableFuture<HttpResponse<String>> future : sent) {
            HttpResponse<String> response = future.join();
            assertEquals(200, response.statusCode(), response.body());
            ids(json.readTree(response.body()), first);
        }
        ids(mutate("PUT", talk, second, 200), first, second);
        // Either the removal wins first (add succeeds), or the add sees capacity (409).
        CompletableFuture<HttpResponse<String>> remove = async("DELETE", talk, first);
        CompletableFuture<HttpResponse<String>> add = async("PUT", talk, third);
        assertEquals(200, remove.join().statusCode());
        int status = add.join().statusCode();
        assertTrue(status == 200 || status == 409);
        if (status == 200) ids(get("/talks/" + talk), second, third);
        else ids(get("/talks/" + talk), second);
        // Subsequent addition always succeeds and is ordered after the retained member.
        ids(mutate("PUT", talk, third, 200), second, third);
    }

    private long speaker() throws Exception {
        return send("POST", "/speakers", "{\"name\":\"Co Speaker\",\"email\":\"" + UUID.randomUUID()
                + "@example.com\"}", 201).get("id").asLong();
    }

    private JsonNode submit(long main, int status) throws Exception {
        return send("POST", "/talks", "{\"title\":\"Together\",\"format\":\"PRESENTATION\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + main + "}", status);
    }

    private JsonNode mutate(String method, long talk, long speaker, int status) throws Exception {
        return send(method, path(talk, speaker), null, status);
    }

    private String path(long talk, long speaker) {
        return "/talks/" + talk + "/co-speakers/" + speaker;
    }

    private CompletableFuture<HttpResponse<String>> async(String method, long talk, long speaker) {
        return http.sendAsync(request(method, path(talk, speaker), null), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        return send("GET", path, null, 200);
    }

    private JsonNode send(String method, String path, String body, int status) throws Exception {
        HttpResponse<String> response = http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + path + " " + response.body());
        return json.readTree(response.body());
    }

    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private void ids(JsonNode talk, long... expected) {
        JsonNode ids = talk.get("coSpeakerIds");
        assertNotNull(ids, talk.toString());
        assertTrue(ids.isArray());
        assertEquals(expected.length, ids.size(), talk.toString());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], ids.get(i).asLong());
    }

    // ---------------------------------------------------------------- lock timeout, isolation, idempotency

    @Test
    void postgresLockNotAvailableIsTranslatedToCannotAcquireLockException() throws Exception {
        long talk = submit(speaker(), 201).get("id").asLong();
        try (Connection holder = TestDatabase.connect(database)) {
            CoSpeakerLocks.lockTalk(holder, talk);
            TransactionTemplate tx = new TransactionTemplate(transactions);
            DataAccessException e = assertThrows(DataAccessException.class,
                    () -> tx.executeWithoutResult(status -> talkDao.lock(talk)));
            // Empirical check of Spring 5.3's translation, which the service relies on only loosely (it matches
            // the SQLSTATE itself): 55P03 -> CannotAcquireLockException.
            assertInstanceOf(CannotAcquireLockException.class, e, e.toString());
            assertEquals("55P03", ((SQLException) e.getMostSpecificCause()).getSQLState());
            assertTrue(LockTimeoutException.isLockNotAvailable(e));
            holder.rollback();
        }
    }

    @Test
    void onlyLockNotAvailableCountsAsLockTimeout() {
        assertTrue(LockTimeoutException.isLockNotAvailable(
                new CannotAcquireLockException("x", new SQLException("lock timeout", "55P03"))));
        // Deadlock, serialization failure and constraint violations must keep their own handling.
        for (String state : Arrays.asList("40P01", "40001", "23505", "23503")) {
            assertFalse(LockTimeoutException.isLockNotAvailable(
                    new CannotAcquireLockException("x", new SQLException("other", state))), state);
        }
        assertFalse(LockTimeoutException.isLockNotAvailable(new CannotAcquireLockException("no sql cause")));
    }

    @Test
    void lockTimeoutAnswers503WithRetryAfterAndChangesNothing() throws Exception {
        long talk = submit(speaker(), 201).get("id").asLong();
        long kept = speaker(), co = speaker();
        ids(mutate("PUT", talk, kept, 200), kept);
        try (Connection holder = TestDatabase.connect(database)) {
            CoSpeakerLocks.lockTalk(holder, talk);
            long start = System.nanoTime();
            CompletableFuture<HttpResponse<String>> put = async("PUT", talk, co);
            CompletableFuture<HttpResponse<String>> delete = async("DELETE", talk, kept);
            for (HttpResponse<String> response : Arrays.asList(put.get(10, TimeUnit.SECONDS),
                    delete.get(10, TimeUnit.SECONDS))) {
                assertEquals(503, response.statusCode(), response.body());
                assertEquals("1", response.headers().firstValue("Retry-After").orElse(null));
                JsonNode body = json.readTree(response.body());
                // Same shape as the app's other errors (Spring Boot's error body).
                assertEquals(503, body.get("status").asInt());
                assertEquals("Service Unavailable", body.get("error").asText());
                assertEquals("talk is busy, retry shortly", body.get("message").asText());
                assertTrue(body.get("path").asText().startsWith("/talks/" + talk + "/co-speakers/"));
                assertNotNull(body.get("timestamp"));
            }
            // lock_timeout applies to each lock wait separately. The first waiter waits on the holder
            // (<= 2 s); the second first queues for the row's tuple lock behind it (<= 2 s), then waits
            // on the holder (<= 2 s). Worst case is therefore ~2 x lock_timeout, plus slack for CI.
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 1900 && millis < 5500, "answered after " + millis + " ms");
            assertEquals(Arrays.asList(kept), memberRows(talk));
            holder.rollback();
        }
        ids(mutate("PUT", talk, co, 200), kept, co);
        CoSpeakerLocks.assertPoolDefaults(dataSource, "read committed");
    }

    @Test
    void mutationsQueuedBehindAHeldLockAllApplyInLockOrder() throws Exception {
        long talk = submit(speaker(), 201).get("id").asLong();
        long first = speaker(), second = speaker();
        CompletableFuture<HttpResponse<String>> a, b;
        try (Connection holder = TestDatabase.connect(database); Connection observer = TestDatabase.connect(database)) {
            CoSpeakerLocks.lockTalk(holder, talk);
            a = async("PUT", talk, first);
            b = async("PUT", talk, second);
            assertEquals(2, CoSpeakerLocks.awaitTalkLockWaiters(observer, 2), "both PUTs wait on the talk lock");
            assertFalse(a.isDone() || b.isDone());
            holder.commit();
        }
        HttpResponse<String> ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
        assertEquals(200, ra.statusCode(), ra.body());
        assertEquals(200, rb.statusCode(), rb.body());
        JsonNode ia = json.readTree(ra.body()).get("coSpeakerIds"), ib = json.readTree(rb.body()).get("coSpeakerIds");
        // Whoever got the lock first saw only itself; the second saw both, in that order.
        JsonNode winner = ia.size() == 1 ? ia : ib, last = ia.size() == 1 ? ib : ia;
        assertEquals(1, winner.size(), ia + " / " + ib);
        assertEquals(2, last.size(), ia + " / " + ib);
        assertEquals(winner.get(0), last.get(0));
        long x = last.get(0).asLong(), y = last.get(1).asLong();
        assertEquals(new HashSet<>(Arrays.asList(first, second)), new HashSet<>(Arrays.asList(x, y)));
        assertEquals(Arrays.asList(x, y), memberRows(talk));
        ids(get("/talks/" + talk), x, y);
        CoSpeakerLocks.assertPoolDefaults(dataSource, "read committed");
    }

    @Test
    void daoAddIsIdempotentAndKeepsAddedOrder() throws Exception {
        long talk = submit(speaker(), 201).get("id").asLong();
        long co = speaker(), later = speaker();
        talkDao.addCoSpeaker(talk, co);
        long order = addedOrder(talk, co);
        talkDao.addCoSpeaker(talk, later);
        assertDoesNotThrow(() -> talkDao.addCoSpeaker(talk, co));
        assertEquals(order, addedOrder(talk, co));
        assertEquals(Arrays.asList(co, later), memberRows(talk));
    }

    private List<Long> memberRows(long talk) {
        return jdbc.queryForList("SELECT speaker_id FROM talk_co_speaker WHERE talk_id = ? ORDER BY added_order",
                Long.class, talk);
    }

    private long addedOrder(long talk, long speaker) {
        return jdbc.queryForObject("SELECT added_order FROM talk_co_speaker WHERE talk_id = ? AND speaker_id = ?",
                Long.class, talk, speaker);
    }
}
