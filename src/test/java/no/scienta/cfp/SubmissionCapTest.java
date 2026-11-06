package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.*;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The 3-talk cap counts every talk a speaker presents, as main speaker or co-speaker, while it's in the running
 * (workflow/specs/submission-cap-cr.md). Real app on a random port, its own database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionCapTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.fresh(registry);
    }

    private static String database;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void coSpeakingCountsTowardsTheCap() throws Exception {
        long s = speaker();
        submit(s, 201);
        submit(s, 201);
        coSpeak(talkBy(speaker()), s, 200);
        assertEquals(3, load(s));
        submit(s, 409);
    }

    @Test
    void atTheCapSubmitIs409() throws Exception {
        long s = speaker();
        for (int i = 0; i < 3; i++) submit(s, 201);
        long before = talkCount();
        JsonNode refused = submit(s, 409);
        // D3: the message names the speaker; the old "a speaker can submit at most 3 talks" is replaced.
        assertTrue(refused.toString().contains("speaker " + s + " already presents 3 talks"), refused.toString());
        assertEquals(before, talkCount());
        assertEquals(3, get("/speakers/" + s + "/talks").size());
    }

    @Test
    void atTheCapAddingAsCoSpeakerIs409() throws Exception {
        long s = speaker();
        for (int i = 0; i < 3; i++) submit(s, 201);
        long talk = talkBy(speaker());
        JsonNode refused = mutate("PUT", talk, s, 409);
        assertTrue(refused.toString().contains("speaker " + s + " already presents 3 talks"), refused.toString());
        ids(get("/talks/" + talk));
        assertEquals(3, load(s));
        // Also when the 3 are all co-speakerships.
        long co = speaker();
        for (int i = 0; i < 3; i++) coSpeak(talkBy(speaker()), co, 200);
        mutate("PUT", talk, co, 409);
        submit(co, 409);
        ids(get("/talks/" + talk));
    }

    @Test
    void withdrawnAndRejectedTalksDontCount() throws Exception {
        // Main speaker: withdrawing or rejecting one of 3 frees a place at once.
        long s = speaker();
        long first = submit(s, 201).get("id").asLong();
        long second = submit(s, 201).get("id").asLong();
        submit(s, 201);
        submit(s, 409);
        withdraw(first, s);
        submit(s, 201);
        submit(s, 409);
        decide(second, "REJECTED");
        coSpeak(talkBy(speaker()), s, 200);
        submit(s, 409);

        // Co-speaker: the main speaker withdrawing, or the committee rejecting, frees the co-speaker's place too.
        long co = speaker();
        long mainA = speaker(), mainB = speaker();
        long a = talkBy(mainA), b = talkBy(mainB);
        coSpeak(a, co, 200);
        coSpeak(b, co, 200);
        submit(co, 201);
        long elsewhere = talkBy(speaker());
        mutate("PUT", elsewhere, co, 409);
        withdraw(a, mainA);
        coSpeak(elsewhere, co, 200);
        mutate("PUT", talkBy(speaker()), co, 409);
        decide(b, "REJECTED");
        submit(co, 201);
        assertEquals(3, load(co));
    }

    @Test
    void waitlistedAndAcceptedTalksStillCount() throws Exception {
        // D1: WAITLISTED and ACCEPTED are in the running.
        long s = speaker();
        long waitlisted = submit(s, 201).get("id").asLong();
        long accepted = submit(s, 201).get("id").asLong();
        submit(s, 201);
        decide(waitlisted, "WAITLISTED");
        rate(accepted, 1);
        rate(accepted, 2);
        decide(accepted, "ACCEPTED");
        submit(s, 409);
        mutate("PUT", talkBy(speaker()), s, 409);
    }

    @Test
    void removingACoSpeakerFreesTheirPlace() throws Exception {
        long co = speaker();
        List<Long> talks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long talk = talkBy(speaker());
            coSpeak(talk, co, 200);
            talks.add(talk);
        }
        long other = talkBy(speaker());
        mutate("PUT", other, co, 409);
        mutate("DELETE", talks.get(0), co, 200);
        coSpeak(other, co, 200);
        mutate("DELETE", talks.get(1), co, 200);
        submit(co, 201);
        assertEquals(3, load(co));
    }

    @Test
    void atTheCapCanJoinAnOutOfTheRunningTalk() throws Exception {
        long s = speaker();
        for (int i = 0; i < 3; i++) submit(s, 201);
        long withdrawnMain = speaker();
        long withdrawn = talkBy(withdrawnMain);
        withdraw(withdrawn, withdrawnMain);
        long rejected = talkBy(speaker());
        decide(rejected, "REJECTED");
        ids(coSpeak(withdrawn, s, 200), s);
        ids(coSpeak(rejected, s, 200), s);
        assertEquals(3, load(s));
        submit(s, 409);
        // The other co-speaker rules still apply to a talk out of the running.
        long full = talkBy(speaker());
        long x = speaker(), y = speaker();
        coSpeak(full, x, 200);
        coSpeak(full, y, 200);
        withdraw(full, mainOf(full));
        mutate("PUT", full, s, 409);
        mutate("PUT", withdrawn, withdrawnMain, 400);
    }

    @Test
    void repeatedAddAtTheCapIsANoOp() throws Exception {
        // D2: already a co-speaker on the talk, at the cap: 200, nothing changes.
        long co = speaker();
        long talk = talkBy(speaker());
        coSpeak(talk, co, 200);
        submit(co, 201);
        submit(co, 201);
        assertEquals(3, load(co));
        ids(coSpeak(talk, co, 200), co);
        ids(get("/talks/" + talk), co);
        assertEquals(3, load(co));
    }

    @Test
    void fullTalkIsCheckedBeforeTheSpeakersCap() throws Exception {
        // D4: both limits apply; the talk's is checked first, so the message is about the talk.
        long s = speaker();
        for (int i = 0; i < 3; i++) submit(s, 201);
        long talk = talkBy(speaker());
        coSpeak(talk, speaker(), 200);
        coSpeak(talk, speaker(), 200);
        JsonNode refused = mutate("PUT", talk, s, 409);
        assertTrue(refused.toString().contains("at most 2 co-speakers"), refused.toString());
    }

    @Test
    void onlyTheAddedSpeakersCapIsChecked() throws Exception {
        // D5: a main speaker or another co-speaker at the cap doesn't stop someone else being added.
        long main = speaker();
        long talk = submit(main, 201).get("id").asLong();
        submit(main, 201);
        submit(main, 201);
        long full = speaker();
        coSpeak(talk, full, 200);
        submit(full, 201);
        submit(full, 201);
        assertEquals(3, load(main));
        assertEquals(3, load(full));
        long fresh = speaker();
        ids(coSpeak(talk, fresh, 200), full, fresh);
    }

    @Test
    void overTheCapKeepsEverythingButGetsNothingNew() throws Exception {
        // R8 and D7: a load above 3 from before this change (co-speaking didn't count), made directly in the database.
        long s = speaker();
        long own = submit(s, 201).get("id").asLong();
        long own2 = submit(s, 201).get("id").asLong();
        submit(s, 201);
        long legacy = talkBy(speaker());
        long legacy2 = talkBy(speaker());
        jdbc.update("INSERT INTO talk_co_speaker (talk_id, speaker_id) VALUES (?, ?)", legacy, s);
        jdbc.update("INSERT INTO talk_co_speaker (talk_id, speaker_id) VALUES (?, ?)", legacy2, s);
        assertEquals(5, load(s));

        submit(s, 409);
        mutate("PUT", talkBy(speaker()), s, 409);
        // Everything is kept, and a decision that keeps a talk in the running doesn't re-check the cap (D6).
        ids(get("/talks/" + legacy), s);
        ids(get("/talks/" + legacy2), s);
        assertEquals(3, get("/speakers/" + s + "/talks").size());
        decide(own, "WAITLISTED");
        assertEquals(5, load(s));

        withdraw(own, s);
        submit(s, 409);
        mutate("DELETE", legacy, s, 200);
        submit(s, 409); // load 3: still at the cap
        decide(own2, "REJECTED");
        submit(s, 201); // load 2 before this one
        submit(s, 409);
    }

    @Test
    void speakerTalkListStillMainOnly() throws Exception {
        long s = speaker();
        long own = submit(s, 201).get("id").asLong();
        long coTalk = talkBy(speaker());
        coSpeak(coTalk, s, 200);
        for (String path : new String[] {"/speakers/" + s + "/talks", "/talks?speakerId=" + s}) {
            JsonNode list = get(path);
            assertEquals(1, list.size(), path);
            assertEquals(own, list.get(0).get("id").asLong(), path);
        }
    }

    @Test
    void concurrentSubmitsAndInvitationsNeverExceedTheCap() throws Exception {
        for (int round = 0; round < 5; round++) {
            long s = speaker();
            List<Long> talks = new ArrayList<>();
            for (int i = 0; i < 4; i++) talks.add(talkBy(speaker()));
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sent.add(http.sendAsync(request("POST", "/talks", talkBody(s)), HttpResponse.BodyHandlers.ofString()));
                sent.add(http.sendAsync(request("PUT", path(talks.get(i), s), null), HttpResponse.BodyHandlers.ofString()));
            }
            int succeeded = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> response = f.get(20, TimeUnit.SECONDS);
                int status = response.statusCode();
                assertTrue(status == 200 || status == 201 || status == 409, status + " " + response.body());
                if (status != 409) succeeded++;
            }
            assertEquals(3, succeeded, "round " + round);
            assertEquals(3, load(s), "round " + round);
        }
    }

    @Test
    void submitWaitsForTheSpeakerLockWithoutATimeout() throws Exception {
        // D8: unlike PUT co-speakers (2 s, then 503), a submit waits for the speaker lock as before.
        long s = speaker();
        CompletableFuture<HttpResponse<String>> submitted;
        try (Connection holder = TestDatabase.connect(database)) {
            holder.setAutoCommit(false);
            holder.createStatement().executeQuery("SELECT id FROM speaker WHERE id = " + s + " FOR UPDATE").close();
            submitted = http.sendAsync(request("POST", "/talks", talkBody(s)), HttpResponse.BodyHandlers.ofString());
            Thread.sleep(2500);
            assertFalse(submitted.isDone(), "the submit should still be waiting for the speaker lock");
            holder.commit();
        }
        HttpResponse<String> response = submitted.get(10, TimeUnit.SECONDS);
        assertEquals(201, response.statusCode(), response.body());
    }

    // ---------------------------------------------------------------- helpers

    /** Talks in the running that the speaker presents, as main speaker or co-speaker, straight from the database. */
    private int load(long speaker) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM talk t WHERE t.status IN ('SUBMITTED', 'WAITLISTED', 'ACCEPTED') "
                + "AND (t.speaker_id = ? OR t.id IN (SELECT talk_id FROM talk_co_speaker WHERE speaker_id = ?))",
                Integer.class, speaker, speaker);
    }

    private long talkCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM talk", Long.class);
    }

    private long mainOf(long talk) throws Exception {
        return get("/talks/" + talk).get("speakerId").asLong();
    }

    private long speaker() throws Exception {
        return send("POST", "/speakers", "{\"name\":\"Capped Speaker\",\"email\":\"" + UUID.randomUUID()
                + "@example.com\"}", 201).get("id").asLong();
    }

    private long talkBy(long main) throws Exception {
        return submit(main, 201).get("id").asLong();
    }

    private String talkBody(long main) {
        return "{\"title\":\"Capped\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + main + "}";
    }

    private JsonNode submit(long main, int status) throws Exception {
        return send("POST", "/talks", talkBody(main), status);
    }

    private JsonNode coSpeak(long talk, long speaker, int status) throws Exception {
        return mutate("PUT", talk, speaker, status);
    }

    private JsonNode mutate(String method, long talk, long speaker, int status) throws Exception {
        return send(method, path(talk, speaker), null, status);
    }

    private void withdraw(long talk, long main) throws Exception {
        send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + main + "}", 200);
    }

    private void decide(long talk, String status) throws Exception {
        send("PUT", "/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}", 200);
    }

    private void rate(long talk, long reviewer) throws Exception {
        send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}", 200);
    }

    private String path(long talk, long speaker) {
        return "/talks/" + talk + "/co-speakers/" + speaker;
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
        assertEquals(expected.length, ids.size(), talk.toString());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], ids.get(i).asLong());
    }
}
