package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Scheduling (workflow/specs/scheduling.md): rules R1-R9 and decisions D1-D11. Reads JSON as text, not with Jackson,
 * so it compiles unchanged across the Spring Boot 4 upgrade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingTest {

    private static final String[][] SLOTS = {
            {"S1", "09:00", "10:00"}, {"S2", "10:15", "11:15"}, {"S3", "11:30", "12:30"},
            {"S4", "13:30", "15:30"}, {"S5", "15:45", "16:45"}};
    private static final int ROUNDS = 3;

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

    // The 15 places are shared by every test in this class: start each one with an empty programme.
    @BeforeEach
    void emptyProgramme() {
        jdbc.update("DELETE FROM programme");
    }

    // R1
    @Test
    void entryShowsRoomSlotAndTimes() throws Exception {
        for (String[] slot : SLOTS) {
            long talk = accepted("PRESENTATION");
            String expected = entry("R1", slot[0], slot[1], slot[2], talk);
            assertEquals(expected, put("R1", slot[0], talk, 200).body());
            assertEquals(expected, get("R1", slot[0], 200).body());
        }
        long workshop = accepted("WORKSHOP");
        assertEquals(entry("R3", "S4", "13:30", "15:30", workshop), put("R3", "S4", workshop, 200).body());
        HttpResponse<String> res = get("R3", "S4", 200);
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    // R2, D8
    @Test
    void putGetDeleteAndPutElsewhere() throws Exception {
        long talk = accepted("LIGHTNING_10");
        put("R2", "S1", talk, 200);
        assertEquals(entry("R2", "S1", "09:00", "10:00", talk), get("R2", "S1", 200).body());

        HttpResponse<String> deleted = send("DELETE", "/programme/R2/S1", null);
        assertEquals(204, deleted.statusCode(), deleted.body());
        assertEquals("", deleted.body());
        get("R2", "S1", 404);
        assertEquals(404, send("DELETE", "/programme/R2/S1", null).statusCode()); // L3: a repeated remove is 404

        assertEquals(entry("R3", "S5", "15:45", "16:45", talk), put("R3", "S5", talk, 200).body());
    }

    // R3, D4
    @Test
    void onlyAcceptedTalks() throws Exception {
        long submitted = talk(speaker(), "PRESENTATION");
        put("R1", "S1", submitted, 409);

        long waitlisted = talk(speaker(), "PRESENTATION");
        decide(waitlisted, "WAITLISTED");
        put("R1", "S1", waitlisted, 409);

        long rejected = talk(speaker(), "PRESENTATION");
        decide(rejected, "REJECTED");
        put("R1", "S1", rejected, 409);

        long withdrawn = accepted("PRESENTATION");
        withdraw(withdrawn);
        put("R1", "S1", withdrawn, 409);

        get("R1", "S1", 404);
    }

    // R4, D5
    @Test
    void workshopsOnlyInTheLongSlot() throws Exception {
        long workshop = accepted("WORKSHOP");
        for (String slot : new String[] {"S1", "S2", "S3", "S5"}) {
            put("R1", slot, workshop, 409);
            get("R1", slot, 404);
        }
        put("R1", "S4", workshop, 200);
        // Everything else fits anywhere, the long slot included.
        put("R2", "S4", accepted("PRESENTATION"), 200);
        put("R3", "S4", accepted("LIGHTNING_20"), 200);
        put("R1", "S1", accepted("LIGHTNING_10"), 200);
    }

    // R5
    @Test
    void oneTalkPerRoomAndSlot() throws Exception {
        long first = accepted("PRESENTATION");
        long second = accepted("PRESENTATION");
        put("R1", "S2", first, 200);
        put("R1", "S2", second, 409);
        assertEquals(entry("R1", "S2", "10:15", "11:15", first), get("R1", "S2", 200).body());
        put("R2", "S2", second, 200);
    }

    // R6, D6
    @Test
    void aTalkIsOnTheProgrammeOnce() throws Exception {
        long talk = accepted("PRESENTATION");
        put("R1", "S3", talk, 200);
        put("R2", "S3", talk, 409);
        put("R1", "S5", talk, 409);
        get("R2", "S3", 404);
        get("R1", "S5", 404);
        // The same talk in the same place again changes nothing.
        assertEquals(entry("R1", "S3", "11:30", "12:30", talk), put("R1", "S3", talk, 200).body());
        assertEquals(1, rows(talk));
        // To move it, take it out first.
        assertEquals(204, send("DELETE", "/programme/R1/S3", null).statusCode());
        put("R1", "S5", talk, 200);
        assertEquals(1, rows(talk));
    }

    // R7
    @Test
    void nobodySpeaksInTwoRoomsAtOnce() throws Exception {
        long ada = speaker(), grace = speaker(), alan = speaker(), edsger = speaker();
        long adaOne = accept(talk(ada, "PRESENTATION"));
        long adaTwo = accept(talk(ada, "PRESENTATION"));
        // main speaker vs main speaker
        put("R1", "S1", adaOne, 200);
        put("R2", "S1", adaTwo, 409);
        put("R2", "S2", adaTwo, 200);

        // co-speaker of the new talk vs main speaker already in the slot
        long graceWithAda = accept(talk(grace, "PRESENTATION"));
        coSpeaker(graceWithAda, ada);
        put("R3", "S2", graceWithAda, 409);

        // main speaker of the new talk vs co-speaker already in the slot
        long alanWithGrace = accept(talk(alan, "PRESENTATION"));
        coSpeaker(alanWithGrace, grace);
        put("R1", "S3", alanWithGrace, 200);
        long graceOwn = accept(talk(grace, "PRESENTATION"));
        put("R2", "S3", graceOwn, 409);
        withdraw(graceOwn); // frees grace's place under the 3-talk cap (submission-cap-cr) for the co-speaking below

        // co-speaker vs co-speaker (grace co-speaks on alanWithGrace in S3)
        long edsgerWithGrace = accept(talk(edsger, "PRESENTATION"));
        coSpeaker(edsgerWithGrace, grace);
        put("R3", "S3", edsgerWithGrace, 409);

        // Different slots are fine for the same people.
        put("R3", "S5", graceWithAda, 200);
        put("R2", "S1", edsgerWithGrace, 200);
    }

    // R8, D11
    @Test
    void laterChangesLeaveTheProgramme() throws Exception {
        long speaker = speaker();
        long talk = accept(talk(speaker, "PRESENTATION"));
        long other = accepted("PRESENTATION");
        put("R1", "S1", talk, 200);
        put("R2", "S1", other, 200);

        // A co-speaker added afterwards who already speaks in the same slot: the programme stays.
        coSpeaker(talk, mainSpeaker(other));
        assertEquals(entry("R1", "S1", "09:00", "10:00", talk), get("R1", "S1", 200).body());
        assertEquals(entry("R2", "S1", "09:00", "10:00", other), get("R2", "S1", 200).body());

        // Withdrawn afterwards: still in its place.
        HttpResponse<String> withdrawn = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}");
        assertEquals(200, withdrawn.statusCode(), withdrawn.body());
        assertEquals(entry("R1", "S1", "09:00", "10:00", talk), get("R1", "S1", 200).body());

        // D6 before D9: putting it in the same place again changes nothing, though it's no longer accepted and a
        // co-speaker now clashes (review finding S1).
        assertEquals(entry("R1", "S1", "09:00", "10:00", talk), put("R1", "S1", talk, 200).body());
        // Anywhere else it's checked like any other talk.
        put("R3", "S2", talk, 409);
    }

    // D1, D2, D3, D9: bad input answers 400 or 404 and changes nothing
    @Test
    void badInputIsRejectedAndChangesNothing() throws Exception {
        long talk = accepted("PRESENTATION");
        // D1: a room or slot that doesn't exist is 404 on every verb (scheduling/check-1, lessons.md L12).
        for (String place : new String[] {"R4/S1", "r1/S1", "R0/S1", "R1/S0", "R1/S6", "R1/s1", "R1/S", "x/y"}) {
            assertEquals(404, send("PUT", "/programme/" + place, "{\"talkId\":" + talk + "}").statusCode(), place);
            assertEquals(404, send("GET", "/programme/" + place, null).statusCode(), place);
            assertEquals(404, send("DELETE", "/programme/" + place, null).statusCode(), place);
        }
        for (String body : new String[] {null, "", "not json", "{}", "[]", "{\"talkId\":null}", "{\"talkId\":\"" + talk + "\"}",
                "{\"talkId\":" + talk + ".5}", "{\"talkId\":" + talk + ".0}", "{\"talkId\":true}", "{\"talkId\":0}",
                "{\"talkId\":-1}", "{\"talkId\":[" + talk + "]}", "{\"talkId\":1e300}", "{\"talkId\":123456789012345678901234567890}"}) {
            HttpResponse<String> res = send("PUT", "/programme/R1/S1", body);
            assertEquals(400, res.statusCode(), body + " -> " + res.body());
        }
        // D3: a talkId in the body that doesn't exist is 400 (scheduling/check-1, lessons.md L13).
        HttpResponse<String> unknown = send("PUT", "/programme/R1/S1", "{\"talkId\":999999}");
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals(400, send("PUT", "/programme/R3/S1", "{\"talkId\":2065989499}").statusCode());
        assertEquals(400, send("PUT", "/programme/R1/S1", "{\"talkId\":9223372036854775807}").statusCode());
        // A place that doesn't exist before the talkId's value and the talk (D9).
        assertEquals(404, send("PUT", "/programme/R4/S1", "{\"talkId\":\"x\"}").statusCode());
        assertEquals(404, send("PUT", "/programme/R4/S1", "{\"talkId\":999999}").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM programme", Integer.class));

        // Extra fields are ignored (D2).
        put("R1", "S1", talk, 200, "{\"talkId\":" + talk + ",\"room\":\"R3\",\"note\":\"x\"}");
        assertEquals(entry("R1", "S1", "09:00", "10:00", talk), get("R1", "S1", 200).body());
    }

    // R9: many talks into one place
    @Test
    void rushForOnePlaceHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            List<Long> talks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                talks.add(accepted("PRESENTATION"));
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (long talk : talks) {
                sent.add(async("PUT", "/programme/R2/S3", "{\"talkId\":" + talk + "}"));
            }
            assertEquals(1, winners(sent), "round " + round);
            long placed = Long.parseLong(value(get("R2", "S3", 200).body(), "talkId"));
            assertTrue(talks.contains(placed));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM programme", Integer.class));
            emptyProgramme();
        }
    }

    // R9: one talk into many places
    @Test
    void rushOfOneTalkIntoManyPlacesHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long talk = accepted("PRESENTATION");
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (String room : new String[] {"R1", "R2", "R3"}) {
                for (String[] slot : SLOTS) {
                    sent.add(async("PUT", "/programme/" + room + "/" + slot[0], "{\"talkId\":" + talk + "}"));
                }
            }
            assertEquals(1, winners(sent), "round " + round);
            assertEquals(1, rows(talk));
            emptyProgramme();
        }
    }

    // R9: talks sharing a speaker into one slot
    @Test
    void rushOfSharedSpeakersIntoOneSlotHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long shared = speaker();
            long mine = accept(talk(shared, "PRESENTATION"));
            long withMe = accept(talk(speaker(), "PRESENTATION"));
            coSpeaker(withMe, shared);
            long alsoWithMe = accept(talk(speaker(), "PRESENTATION"));
            coSpeaker(alsoWithMe, shared);
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            sent.add(async("PUT", "/programme/R1/S2", "{\"talkId\":" + mine + "}"));
            sent.add(async("PUT", "/programme/R2/S2", "{\"talkId\":" + withMe + "}"));
            sent.add(async("PUT", "/programme/R3/S2", "{\"talkId\":" + alsoWithMe + "}"));
            assertEquals(1, winners(sent), "round " + round);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM programme", Integer.class));
            emptyProgramme();
        }
    }

    // API table: the slot stays locked for 2 s -> 503 with Retry-After: 1, nothing stored
    @Test
    void lockedSlotAnswers503AndChangesNothing() throws Exception {
        long talk = accepted("PRESENTATION");
        try (Connection holder = TestDatabase.connect(database)) {
            holder.setAutoCommit(false);
            try (PreparedStatement s = holder.prepareStatement("SELECT slot FROM programme_slot WHERE slot = 'S5' FOR UPDATE")) {
                s.executeQuery();
            }
            HttpResponse<String> res = send("PUT", "/programme/R1/S5", "{\"talkId\":" + talk + "}");
            assertEquals(503, res.statusCode(), res.body());
            assertEquals("1", res.headers().firstValue("Retry-After").orElse(null));
            assertTrue(res.body().contains("\"message\":\"slot S5 is busy, retry shortly\""), res.body());
            holder.rollback();
        }
        get("R1", "S5", 404);
        put("R1", "S5", talk, 200);
    }

    // ---------------------------------------------------------------- helpers

    private static String entry(String room, String slot, String start, String end, long talk) {
        return "{\"room\":\"" + room + "\",\"slot\":\"" + slot + "\",\"start\":\"" + start + "\",\"end\":\"" + end
                + "\",\"talkId\":" + talk + "}";
    }

    private HttpResponse<String> put(String room, String slot, long talk, int status) throws Exception {
        return put(room, slot, talk, status, "{\"talkId\":" + talk + "}");
    }

    private HttpResponse<String> put(String room, String slot, long talk, int status, String body) throws Exception {
        HttpResponse<String> res = send("PUT", "/programme/" + room + "/" + slot, body);
        assertEquals(status, res.statusCode(), "PUT " + room + "/" + slot + " talk " + talk + " -> " + res.body());
        return res;
    }

    private HttpResponse<String> get(String room, String slot, int status) throws Exception {
        HttpResponse<String> res = send("GET", "/programme/" + room + "/" + slot, null);
        assertEquals(status, res.statusCode(), "GET " + room + "/" + slot + " -> " + res.body());
        return res;
    }

    private int rows(long talk) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM programme WHERE talk_id = ?", Integer.class, talk);
    }

    private static int winners(List<CompletableFuture<HttpResponse<String>>> sent) {
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                ok++;
            }
        }
        return ok;
    }

    private long accepted(String format) throws Exception {
        return accept(talk(speaker(), format));
    }

    private long accept(long talk) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            HttpResponse<String> rated = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}");
            assertEquals(200, rated.statusCode(), rated.body());
        }
        decide(talk, "ACCEPTED");
        return talk;
    }

    private void decide(long talk, String status) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private void withdraw(long talk) throws Exception {
        HttpResponse<String> res = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + mainSpeaker(talk) + "}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private void coSpeaker(long talk, long speaker) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/co-speakers/" + speaker, null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private long mainSpeaker(long talk) throws Exception {
        return Long.parseLong(value(send("GET", "/talks/" + talk, null).body(), "speakerId"));
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Scheduled\",\"email\":\"sched-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(value(res.body(), "id"));
    }

    private long talk(long speaker, String format) throws Exception {
        HttpResponse<String> res = send("POST", "/talks", "{\"title\":\"On the programme\",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(value(res.body(), "id"));
    }

    /** The first value of a numeric field. */
    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1);
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
