package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Committee decisions and withdrawal: allowed transitions, the two-reviewer rule, and simultaneous decisions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptanceTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void acceptingNeedsTwoDifferentReviewers() throws Exception {
        String talk = newTalk(newSpeaker());
        assertEquals(409, decide(talk, "ACCEPTED").statusCode());
        rate(talk, 1);
        rate(talk, 1);
        assertEquals(409, decide(talk, "ACCEPTED").statusCode());
        rate(talk, 2);
        HttpResponse<String> ok = decide(talk, "ACCEPTED");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"status\":\"ACCEPTED\""), ok.body());
    }

    @Test
    void waitlistedCanBeDecidedLaterButDecisionsAreFinal() throws Exception {
        String talk = newTalk(newSpeaker());
        assertEquals(200, decide(talk, "WAITLISTED").statusCode());
        assertEquals(409, decide(talk, "WAITLISTED").statusCode());
        assertEquals(200, decide(talk, "REJECTED").statusCode());
        assertEquals(409, decide(talk, "ACCEPTED").statusCode());
        assertTrue(send("GET", "/talks/" + talk, null).body().contains("\"status\":\"REJECTED\""));
    }

    @Test
    void badInputAndUnknownTalk() throws Exception {
        String talk = newTalk(newSpeaker());
        assertEquals(400, decide(talk, "SUBMITTED").statusCode());
        assertEquals(400, decide(talk, "WITHDRAWN").statusCode());
        assertEquals(400, send("PUT", "/talks/" + talk + "/status", "{}").statusCode());
        assertEquals(404, decide("999999", "REJECTED").statusCode());
        assertEquals(404, send("POST", "/talks/999999/withdraw", "{\"speakerId\":1}").statusCode());
    }

    @Test
    void onlyTheSpeakerWithdrawsAndNotAfterRejection() throws Exception {
        String speaker = newSpeaker();
        String talk = newTalk(speaker);
        assertEquals(403, withdraw(talk, newSpeaker()).statusCode());
        assertEquals(400, send("POST", "/talks/" + talk + "/withdraw", "{}").statusCode());
        HttpResponse<String> ok = withdraw(talk, speaker);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"status\":\"WITHDRAWN\""), ok.body());
        assertEquals(409, withdraw(talk, speaker).statusCode());
        assertEquals(409, decide(talk, "REJECTED").statusCode());

        String rejected = newTalk(speaker);
        decide(rejected, "REJECTED");
        assertEquals(409, withdraw(rejected, speaker).statusCode());
    }

    @Test
    void aSpeakerIdThatIsNotAWholeNumberIsBadInputAndChangesNothing() throws Exception {
        String speaker = newSpeaker();
        String talk = newTalk(speaker);
        for (String bad : new String[] {"1.5", speaker + ".5", speaker + ".0", speaker + "e0", "\"" + speaker + "\"", "null", "true",
                "18446744073709551617", "-1", "0", "-0", "[]", "{}"}) {
            HttpResponse<String> r = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + bad + "}");
            assertEquals(400, r.statusCode(), bad + ": " + r.body());
        }
        assertTrue(send("GET", "/talks/" + talk, null).body().contains("\"status\":\"SUBMITTED\""));
        assertEquals(200, withdraw(talk, speaker).statusCode());
    }

    @Test
    void simultaneousDecisionsLeaveOneWinner() throws Exception {
        String talk = newTalk(newSpeaker());
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (String status : new String[] {"REJECTED", "WAITLISTED", "REJECTED", "WAITLISTED", "REJECTED", "WAITLISTED"}) {
            sent.add(http.sendAsync(request("PUT", "/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}"),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            int code = f.join().statusCode();
            assertTrue(code == 200 || code == 409, "status " + code);
            if (code == 200) {
                ok++;
            }
        }
        // SUBMITTED can take a WAITLISTED then a REJECTED, or a REJECTED straight away: never more than two decisions.
        assertTrue(ok >= 1 && ok <= 2, "decisions applied: " + ok);
        String status = send("GET", "/talks/" + talk, null).body();
        assertTrue(status.contains("\"status\":\"REJECTED\"") || status.contains("\"status\":\"WAITLISTED\""), status);
    }

    private HttpResponse<String> decide(String talk, String status) throws Exception {
        return send("PUT", "/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}");
    }

    private HttpResponse<String> withdraw(String talk, String speaker) throws Exception {
        return send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}");
    }

    private void rate(String talk, int reviewer) throws Exception {
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}").statusCode());
    }

    private String newSpeaker() throws Exception {
        return id(send("POST", "/speakers", "{\"name\":\"Ada\",\"email\":\"ada-" + UUID.randomUUID() + "@example.com\"}"));
    }

    private String newTalk(String speaker) throws Exception {
        return id(send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
    }

    private static String id(HttpResponse<String> res) {
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return http.send(request(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
