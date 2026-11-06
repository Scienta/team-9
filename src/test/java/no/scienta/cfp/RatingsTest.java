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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Ratings spec over HTTP: validation, replace-on-rerate, rounding, ratedAtDisplay and the ranking. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aRatingIsEchoedWithTheUsShortUtcTime() throws Exception {
        long talk = talk(speaker());
        HttpResponse<String> res = rate(talk, 1, "{\"score\":4}");
        assertEquals(200, res.statusCode(), res.body());
        JsonNode body = json.readTree(res.body());
        assertEquals(talk, body.get("talkId").asLong());
        assertEquals(1, body.get("reviewerId").asLong());
        assertEquals(4, body.get("score").asInt());
        assertTrue(body.get("ratedAtDisplay").asText().matches("\\d{1,2}/\\d{1,2}/\\d{2}, \\d{1,2}:\\d{2} [AP]M"),
                res.body());
    }

    @Test
    void reratingReplacesAndTheAverageIsRoundedHalfUp() throws Exception {
        long talk = talk(speaker());
        JsonNode unrated = get("/talks/" + talk);
        assertTrue(unrated.get("averageRating").isNull(), unrated.toString());
        assertEquals(0, unrated.get("ratingCount").asInt());

        rate(talk, 1, "{\"score\":1}");
        int reviewer = 1;
        for (int score : new int[] {4, 4, 4, 5}) {
            assertEquals(200, rate(talk, reviewer++, "{\"score\":" + score + "}").statusCode());
        }
        JsonNode rated = get("/talks/" + talk);
        assertEquals(4, rated.get("ratingCount").asInt());
        assertEquals(4.3, rated.get("averageRating").asDouble());
    }

    @Test
    void onlyWholeScoresFrom1To5FromPositiveReviewersOnExistingTalks() throws Exception {
        long talk = talk(speaker());
        for (String bad : new String[] {"{\"score\":0}", "{\"score\":6}", "{\"score\":\"3\"}", "{\"score\":3.5}", "{}"}) {
            assertEquals(400, rate(talk, 1, bad).statusCode(), bad);
        }
        assertEquals(400, rate(talk, 0, "{\"score\":3}").statusCode());
        assertEquals(404, rate(talk + 1000, 1, "{\"score\":3}").statusCode());
        assertEquals(200, rate(talk, 1, "{\"score\":1}").statusCode());
        assertEquals(200, rate(talk, 2, "{\"score\":5}").statusCode());
        assertEquals(2, get("/talks/" + talk).get("ratingCount").asInt());
    }

    @Test
    void rankingPutsTheBestAverageFirstAndUnratedLast() throws Exception {
        long speaker = speaker();
        long unrated = talk(speaker), good = talk(speaker), best = talk(speaker);
        rate(good, 1, "{\"score\":3}");
        rate(best, 1, "{\"score\":5}");
        assertEquals(List.of(best, good, unrated), ids(get("/talks?sort=rating&speakerId=" + speaker)));
        assertEquals(List.of(unrated, good, best), ids(get("/talks?speakerId=" + speaker)));
        assertEquals(400, send("GET", "/talks?sort=title", null).statusCode());
    }

    private HttpResponse<String> rate(long talk, long reviewer, String body) throws Exception {
        return send("PUT", "/talks/" + talk + "/ratings/" + reviewer, body);
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Barbara Liskov\",\"email\":\"barbara-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speaker) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = send("GET", path, null);
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
    }

    private static List<Long> ids(JsonNode talks) {
        List<Long> ids = new ArrayList<>();
        talks.forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
