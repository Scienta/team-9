package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ratings change request D2: /admin/stats averages the talks' averages and rounds to 1 decimal half up, on the exact
 * decimal sum. Its own database, so the only rated talks are this test's (review finding E1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsCrStatsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void statsRoundTheExactMeanOfTheTalkAverages() throws Exception {
        rated(1, 1, 1, 1, 1, 2, 2, 2, 2);   // one 1 and one 2 left out: 10 / 7 = 1.43
        rated(1, 2, 2);                     // 5 / 3 = 1.67
        // (1.43 + 1.67) / 2 = 1.55 exactly -> 1.6; summed as doubles it is 1.5499999999999998 -> 1.5
        String stats = send("GET", "/admin/stats", null).body();
        Matcher m = Pattern.compile("\"averageRating\":([\\d.]+)").matcher(stats);
        assertTrue(m.find(), stats);
        assertEquals("1.6", m.group(1), stats);
    }

    private void rated(int... scores) throws Exception {
        HttpResponse<String> speaker = send("POST", "/speakers",
                "{\"name\":\"Stats\",\"email\":\"stats-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, speaker.statusCode(), speaker.body());
        String speakerId = speaker.body().replaceAll(".*\"id\":(\\d+).*", "$1");
        HttpResponse<String> talk = send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}");
        assertEquals(201, talk.statusCode(), talk.body());
        String talkId = talk.body().replaceAll("^\\{\"id\":(\\d+).*", "$1");
        for (int i = 0; i < scores.length; i++) {
            HttpResponse<String> res = send("PUT", "/talks/" + talkId + "/ratings/" + (i + 1), "{\"score\":" + scores[i] + "}");
            assertEquals(200, res.statusCode(), res.body());
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
