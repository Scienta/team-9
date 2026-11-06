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

/**
 * A speaker submits at most 3 talks, also when the submissions arrive at the same time. Runs the real app on a random
 * port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TalksTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void concurrentSubmissionsNeverExceedThreeTalks() throws Exception {
        String speaker = newSpeaker();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(http.sendAsync(request("POST", "/talks",
                    "{\"title\":\"Talk " + i + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            }
        }
        assertEquals(3, created);
        HttpResponse<String> talks = http.send(request("GET", "/speakers/" + speaker + "/talks", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(3, count(talks.body()));
    }

    @Test
    void timestampsUseLegacyFormats() throws Exception {
        String speaker = newSpeaker();
        HttpResponse<String> res = http.send(request("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().matches(".*\"submittedAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00\".*"), res.body());
        assertTrue(res.body().matches(".*\"submittedOn\":\"\\d{4}-\\d{2}-\\d{2}\".*"), res.body());
        assertTrue(res.body().contains("\"durationMinutes\":120"), res.body());
    }

    @Test
    void durationComesFromTheFormat() throws Exception {
        String speaker = newSpeaker();
        String[][] cases = {{"LIGHTNING_10", "10"}, {"LIGHTNING_20", "20"}, {"PRESENTATION", "45"}};
        for (String[] c : cases) {
            HttpResponse<String> res = submit("{\"title\":\"T\",\"format\":\"" + c[0]
                    + "\",\"level\":\"ADVANCED\",\"speakerId\":" + speaker + "}");
            assertEquals(201, res.statusCode(), res.body());
            assertTrue(res.body().contains("\"durationMinutes\":" + c[1] + ","), res.body());
            assertTrue(res.body().contains("\"status\":\"SUBMITTED\""), res.body());
        }
    }

    @Test
    void invalidSubmissionsAreBadRequestAndCreateNothing() throws Exception {
        String speaker = newSpeaker();
        String[] bodies = {
                "{\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"  \",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"T\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"T\",\"format\":\"KEYNOTE\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"T\",\"format\":\"presentation\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"EXPERT\",\"speakerId\":" + speaker + "}",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\"}",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":999999}",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":\"x\"}",
                "not json",
        };
        for (String body : bodies) {
            HttpResponse<String> res = submit(body);
            assertEquals(400, res.statusCode(), body + " -> " + res.body());
        }
        HttpResponse<String> talks = http.send(request("GET", "/speakers/" + speaker + "/talks", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("[]", talks.body());
    }

    @Test
    void unknownTalkOrSpeakerIsNotFound() throws Exception {
        assertEquals(404, http.send(request("GET", "/talks/999999", null), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, http.send(request("GET", "/speakers/999999/talks", null), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, http.send(request("GET", "/talks/abc", null), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private HttpResponse<String> submit(String json) throws Exception {
        return http.send(request("POST", "/talks", json), HttpResponse.BodyHandlers.ofString());
    }

    private String newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(request("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
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

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
