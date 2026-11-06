package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme export (workflow/specs/programme-export.md): rules R1-R8 and decisions D1-D8. Reads JSON as text, as
 * SchedulingTest does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ListQueriesTest.CountingDataSource.class)
class ProgrammeExportTest {

    private static final String HEADER = "slot,start,end,room,talkId,title,format,speakers\n";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyProgramme() {
        jdbc.update("DELETE FROM programme");
    }

    // R8
    @Test
    void emptyProgrammeIsEmptyListAndHeader() throws Exception {
        HttpResponse<String> json = get("/programme", null);
        assertEquals("[]", json.body());
        assertTrue(json.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(HEADER, get("/programme.csv", null).body());
    }

    // R1, D1: one row per scheduled talk, nothing else; a withdrawn talk still on the programme is exported
    @Test
    void oneRowPerScheduledTalk() throws Exception {
        long speaker = speaker("Grace Hopper");
        long scheduled = accepted(speaker, "Scheduled", "PRESENTATION");
        accepted(speaker("Not Scheduled"), "Accepted only", "PRESENTATION");
        talk(speaker("Submitted"), "Submitted only", "PRESENTATION");
        long withdrawn = accepted(speaker("Withdrawer"), "Withdrawn later", "LIGHTNING_20");
        schedule("R1", "S1", scheduled);
        schedule("R2", "S1", withdrawn);
        withdraw(withdrawn);

        String json = get("/programme", null).body();
        assertEquals(2, count(json, "\"talkId\":"), json);
        assertTrue(json.contains("\"talkId\":" + scheduled + ","), json);
        assertTrue(json.contains("\"talkId\":" + withdrawn + ","), json);

        String csv = get("/programme.csv", null).body();
        assertEquals(HEADER
                + "S1,09:00,10:00,R1," + scheduled + ",Scheduled,PRESENTATION,Grace Hopper\n"
                + "S1,09:00,10:00,R2," + withdrawn + ",Withdrawn later,LIGHTNING_20,Withdrawer\n", csv);

        // taken out -> gone from both
        assertEquals(204, send("DELETE", "/programme/R2/S1", null, null).statusCode());
        assertEquals(1, count(get("/programme", null).body(), "\"talkId\":"));
        assertEquals(2, get("/programme.csv", null).body().split("\n").length);
    }

    // R2: slot (start) first, then room; same order in JSON and CSV
    @Test
    void orderedBySlotThenRoom() throws Exception {
        // scheduled in a scrambled order so neither insertion order nor talk id gives the expected order
        String[][] places = {{"R3", "S5"}, {"R1", "S4"}, {"R2", "S1"}, {"R3", "S1"}, {"R1", "S1"}, {"R2", "S4"},
                {"R1", "S2"}, {"R3", "S3"}};
        for (String[] p : places) {
            schedule(p[0], p[1], accepted(speaker("Speaker " + p[0] + p[1]), p[0] + p[1], "PRESENTATION"));
        }
        String expected = "R1S1 R2S1 R3S1 R1S2 R3S3 R1S4 R2S4 R3S5";

        StringBuilder jsonOrder = new StringBuilder();
        Matcher m = Pattern.compile("\"slot\":\"(S\\d)\",\"start\":\"[^\"]*\",\"end\":\"[^\"]*\",\"room\":\"(R\\d)\"")
                .matcher(get("/programme", null).body());
        while (m.find()) {
            jsonOrder.append(jsonOrder.length() == 0 ? "" : " ").append(m.group(2)).append(m.group(1));
        }
        assertEquals(expected, jsonOrder.toString());

        StringBuilder csvOrder = new StringBuilder();
        String[] lines = get("/programme.csv", null).body().split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split(",");
            csvOrder.append(csvOrder.length() == 0 ? "" : " ").append(f[3]).append(f[0]);
        }
        assertEquals(expected, csvOrder.toString());
    }

    // R3, R4, D2, D7, D8: every field, current title/format/speakers, names main first then co in added order
    @Test
    void rowShowsCurrentTalk() throws Exception {
        long grace = speaker("Grace Hopper");
        long ada = speaker("Ada Lovelace");
        long alan = speaker("Alan Turing");
        long workshop = accepted(grace, "Compilers by hand", "WORKSHOP");
        long lightning = accepted(speaker("Solo"), "Ten minutes", "LIGHTNING_10");
        schedule("R2", "S4", workshop);
        schedule("R3", "S2", lightning);
        // co-speakers added after scheduling (R8 of scheduling: allowed) still show, in added order, not id order
        coSpeaker(workshop, alan);
        coSpeaker(workshop, ada);
        rename(grace, "Grace B. Hopper");

        assertEquals("[" + row("S2", "10:15", "11:15", "R3", lightning, "Ten minutes", "LIGHTNING_10", "\"Solo\"") + ","
                + row("S4", "13:30", "15:30", "R2", workshop, "Compilers by hand", "WORKSHOP",
                        "\"Grace B. Hopper\",\"Alan Turing\",\"Ada Lovelace\"") + "]",
                get("/programme", null).body());
        assertEquals(HEADER
                + "S2,10:15,11:15,R3," + lightning + ",Ten minutes,LIGHTNING_10,Solo\n"
                + "S4,13:30,15:30,R2," + workshop + ",Compilers by hand,WORKSHOP,Grace B. Hopper; Alan Turing; Ada Lovelace\n",
                get("/programme.csv", null).body());

        // a co-speaker removed later is gone at once
        assertEquals(200, send("DELETE", "/talks/" + workshop + "/co-speakers/" + alan, null, null).statusCode());
        assertTrue(get("/programme.csv", null).body().endsWith(",WORKSHOP,Grace B. Hopper; Ada Lovelace\n"));
        assertTrue(get("/programme", null).body().contains("\"speakers\":[\"Grace B. Hopper\",\"Ada Lovelace\"]}"));
    }

    // R5, D4: text/csv in UTF-8, also when JSON is asked for
    @Test
    void csvIsTextCsvEvenWhenJsonIsAsked() throws Exception {
        schedule("R1", "S3", accepted(speaker("Bjørn Ærøy"), "Smørbrød og Ålesund – ein føredrag", "PRESENTATION"));
        for (String accept : new String[] {null, "application/json", "text/csv", "*/*", "text/html"}) {
            HttpResponse<byte[]> res = bytes("/programme.csv", accept);
            assertEquals(200, res.statusCode(), "Accept " + accept);
            assertEquals("text/csv;charset=UTF-8", res.headers().firstValue("Content-Type").orElse("").replace(" ", ""),
                    "Accept " + accept);
            String body = new String(res.body(), StandardCharsets.UTF_8);
            assertTrue(body.startsWith(HEADER), body);
            assertTrue(body.contains(",Smørbrød og Ålesund – ein føredrag,PRESENTATION,Bjørn Ærøy\n"), body);
            assertFalse(body.startsWith("\uFEFF"), "no BOM");
            assertEquals("attachment; filename=programme.csv", res.headers().firstValue("Content-Disposition").orElse(null));
        }
    }

    // D3: /programme is JSON whatever is asked for
    @Test
    void jsonWhateverTheAcceptHeader() throws Exception {
        for (String accept : new String[] {null, "application/json", "text/csv", "*/*", "text/html"}) {
            HttpResponse<String> res = get("/programme", accept);
            assertEquals("[]", res.body(), "Accept " + accept);
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), "Accept " + accept);
        }
    }

    // R6: exact header, \n only, every line ends in \n, no blank line at the end
    @Test
    void csvHeaderAndLineEndings() throws Exception {
        schedule("R1", "S1", accepted(speaker("A"), "First", "PRESENTATION"));
        schedule("R2", "S1", accepted(speaker("B"), "Second", "PRESENTATION"));
        String csv = get("/programme.csv", null).body();
        assertTrue(csv.startsWith(HEADER), csv);
        assertFalse(csv.contains("\r"), csv);
        assertTrue(csv.endsWith("\n"), csv);
        assertFalse(csv.endsWith("\n\n"), csv);
        assertEquals(3, csv.split("\n", -1).length - 1, csv);
    }

    // R7, D5: quoted only when needed, quotes doubled, line breaks kept inside quotes
    @Test
    void csvQuotesOnlyWhenNeeded() throws Exception {
        long comma = accepted(speaker("Hopper, Grace"), "Kotlin, \"really\" fast", "PRESENTATION");
        long co = speaker("Ada \"Countess\" Lovelace");
        coSpeaker(comma, co);
        long plain = accepted(speaker("O'Brien; Jr"), "  Spaces; semicolons 'and' more  ", "PRESENTATION");
        long newline = accepted(speaker("Line"), "Two\nlines\r\nhere", "PRESENTATION");
        long quoteOnly = accepted(speaker("Q"), "Say \"hi\"", "PRESENTATION");
        schedule("R1", "S1", comma);
        schedule("R2", "S1", plain);
        schedule("R3", "S1", newline);
        schedule("R1", "S2", quoteOnly);

        assertEquals(HEADER
                + "S1,09:00,10:00,R1," + comma + ",\"Kotlin, \"\"really\"\" fast\",PRESENTATION,\"Hopper, Grace; Ada \"\"Countess\"\" Lovelace\"\n"
                + "S1,09:00,10:00,R2," + plain + ",  Spaces; semicolons 'and' more  ,PRESENTATION,O'Brien; Jr\n"
                + "S1,09:00,10:00,R3," + newline + ",\"Two\nlines\r\nhere\",PRESENTATION,Line\n"
                + "S2,10:15,11:15,R1," + quoteOnly + ",\"Say \"\"hi\"\"\",PRESENTATION,Q\n",
                get("/programme.csv", null).body());
        // JSON is plain JSON: the speakers are separate strings
        assertTrue(get("/programme", null).body().contains("\"speakers\":[\"Hopper, Grace\",\"Ada \\\"Countess\\\" Lovelace\"]"));
    }

    // R4: a co-speaker removed and added again goes last (cases from team 7's green suite)
    @Test
    void coSpeakerAddedAgainGoesLast() throws Exception {
        long ada = speaker("Ada");
        long alan = speaker("Alan");
        long talk = accepted(speaker("Grace"), "Again", "PRESENTATION");
        coSpeaker(talk, ada);
        coSpeaker(talk, alan);
        schedule("R1", "S1", talk);
        assertEquals(200, send("DELETE", "/talks/" + talk + "/co-speakers/" + ada, null, null).statusCode());
        coSpeaker(talk, ada);
        assertTrue(get("/programme", null).body().contains("\"speakers\":[\"Grace\",\"Alan\",\"Ada\"]"));
        assertTrue(get("/programme.csv", null).body().endsWith(",Grace; Alan; Ada\n"));
    }

    // API: a trailing slash is the same JSON list, as on every other endpoint (CompatibilityConfig)
    @Test
    void trailingSlashIsTheSame() throws Exception {
        schedule("R2", "S3", accepted(speaker("Slash"), "Trailing", "PRESENTATION"));
        assertEquals(get("/programme", null).body(), get("/programme/", null).body());
    }

    // D6: query parameters are ignored
    @Test
    void queryParametersAreIgnored() throws Exception {
        long talk = accepted(speaker("Q"), "Only one", "PRESENTATION");
        schedule("R2", "S2", talk);
        assertEquals(get("/programme", null).body(), get("/programme?room=R1&slot=S1", null).body());
        assertEquals(get("/programme.csv", null).body(), get("/programme.csv?room=R1", null).body());
    }

    // API: other methods are the framework's 405
    @Test
    void otherMethodsAre405() throws Exception {
        assertEquals(405, send("POST", "/programme", "{}", null).statusCode());
        assertEquals(405, send("DELETE", "/programme.csv", null, null).statusCode());
    }

    // Concurrency and data, lessons.md L11: a full programme (15 talks, each with two co-speakers) takes a fixed number
    // of statements, not one or more per row
    @Test
    void fullProgrammeTakesAFixedNumberOfQueries() throws Exception {
        int n = 0;
        for (String slot : new String[] {"S1", "S2", "S3", "S4", "S5"}) {
            for (String room : new String[] {"R1", "R2", "R3"}) {
                long talk = accepted(speaker("Main " + n), "Talk " + n++, "PRESENTATION");
                schedule(room, slot, talk);
                if (room.equals("R1")) { // added after scheduling, so R7 of scheduling doesn't apply
                    // New co-speakers per talk: one speaker on 5 talks would be over the 3-talk cap (submission-cap-cr).
                    coSpeaker(talk, speaker("Co One " + slot));
                    coSpeaker(talk, speaker("Co Two " + slot));
                }
            }
        }
        for (String path : new String[] {"/programme", "/programme.csv"}) {
            ListQueriesTest.STATEMENTS.set(0);
            HttpResponse<String> res = get(path, null);
            int statements = ListQueriesTest.STATEMENTS.get();
            assertTrue(statements >= 1, path + ": no statements counted; is the counting DataSource wired?");
            assertTrue(statements <= 3, path + " ran " + statements + " SQL statements for 15 rows");
            assertTrue(res.body().contains("Co One") && res.body().contains("Talk 14"), res.body());
        }
    }

    // D9: a scheduled talk whose main speaker row is missing (old data, no foreign key) still gets its row
    @Test
    void talkWithoutMainSpeakerRowStillExported() throws Exception {
        long co = speaker("Only Co");
        Long talk = jdbc.queryForObject("INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                + "VALUES ('Orphan', 'PRESENTATION', 45, 'BEGINNER', 'ACCEPTED', 2147483647, 0) RETURNING id", Long.class);
        coSpeaker(talk, co);
        schedule("R3", "S5", talk);
        assertEquals("[" + row("S5", "15:45", "16:45", "R3", talk, "Orphan", "PRESENTATION", "\"Only Co\"") + "]",
                get("/programme", null).body());
        assertEquals(HEADER + "S5,15:45,16:45,R3," + talk + ",Orphan,PRESENTATION,Only Co\n", get("/programme.csv", null).body());
    }

    // ---------------------------------------------------------------- helpers

    private static String row(String slot, String start, String end, String room, long talk, String title, String format,
                              String speakers) {
        return "{\"slot\":\"" + slot + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\",\"room\":\"" + room
                + "\",\"talkId\":" + talk + ",\"title\":\"" + title + "\",\"format\":\"" + format + "\",\"speakers\":["
                + speakers + "]}";
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        HttpResponse<String> res = send("GET", path, null, accept);
        assertEquals(200, res.statusCode(), path + " -> " + res.body());
        return res;
    }

    private HttpResponse<byte[]> bytes(String path, String accept) throws Exception {
        return http.send(request("GET", path, null, accept), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void schedule(String room, String slot, long talk) throws Exception {
        HttpResponse<String> res = send("PUT", "/programme/" + room + "/" + slot, "{\"talkId\":" + talk + "}", null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private long accepted(long speaker, String title, String format) throws Exception {
        long talk = talk(speaker, title, format);
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            HttpResponse<String> rated = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}", null);
            assertEquals(200, rated.statusCode(), rated.body());
        }
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}", null);
        assertEquals(200, res.statusCode(), res.body());
        return talk;
    }

    private void withdraw(long talk) throws Exception {
        long speaker = Long.parseLong(value(send("GET", "/talks/" + talk, null, null).body(), "speakerId"));
        HttpResponse<String> res = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}", null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private void coSpeaker(long talk, long speaker) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/co-speakers/" + speaker, null, null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private void rename(long speaker, String name) throws Exception {
        HttpResponse<String> res = send("PUT", "/speakers/" + speaker,
                "{\"name\":" + json(name) + ",\"email\":\"renamed-" + UUID.randomUUID() + "@example.com\"}", null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private long speaker(String name) throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":" + json(name) + ",\"email\":\"export-" + UUID.randomUUID() + "@example.com\"}", null);
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(value(res.body(), "id"));
    }

    private long talk(long speaker, String title, String format) throws Exception {
        HttpResponse<String> res = send("POST", "/talks", "{\"title\":" + json(title) + ",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}", null);
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(value(res.body(), "id"));
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String body, String accept) throws Exception {
        return http.send(request(method, path, body, accept), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(String method, String path, String body, String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (accept != null) {
            b.header("Accept", accept);
        }
        return b.build();
    }
}
