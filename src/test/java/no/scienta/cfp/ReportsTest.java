package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The committee's admin exports. Not public API, but the committee depends on them. Own database, fixed contents. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void statsAndCsvDescribeTheTalks() throws Exception {
        long speaker = id(send("POST", "/speakers",
                "{\"name\":\"Hopper; Grace\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"));
        long rated = id(send("POST", "/talks",
                "{\"title\":\"Nanoseconds\",\"format\":\"LIGHTNING_10\",\"level\":\"ADVANCED\",\"speakerId\":" + speaker + "}"));
        long unrated = id(send("POST", "/talks",
                "{\"title\":\"COBOL\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
        assertEquals(200, send("PUT", "/talks/" + rated + "/ratings/1", "{\"score\":4}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + rated + "/ratings/2", "{\"score\":5}").statusCode());

        HttpResponse<String> statsRes = send("GET", "/admin/stats", null);
        assertEquals(200, statsRes.statusCode(), statsRes.body());
        JsonNode stats = json.readTree(statsRes.body());
        assertEquals(1, stats.get("speakers").asInt());
        assertEquals(1, stats.get("speakersWithTalks").asInt());
        assertEquals(2, stats.get("talks").asInt());
        assertEquals(130 / 60.0, stats.get("totalHours").asDouble());
        assertEquals(4.5, stats.get("averageRating").asDouble());

        HttpResponse<String> csv = send("GET", "/admin/talks.csv", null);
        assertEquals(200, csv.statusCode(), csv.body());
        assertEquals("text/csv;charset=UTF-8", csv.headers().firstValue("Content-Type").orElse("").replace(" ", ""));
        String[] lines = csv.body().split("\n");
        assertEquals(3, lines.length, csv.body());
        assertEquals("id;title;format;minutes;level;speaker;status;submitted;rating;ratings", lines[0]);
        String date = "\\d{1,2}\\. [a-zæøå]+\\.? \\d{4}";
        assertTrue(lines[1].matches(rated + ";Nanoseconds;Lightning 10;10;Advanced;\"Hopper; Grace\";SUBMITTED;" + date
                + ";4,5;2"), lines[1]);
        assertTrue(lines[2].matches(unrated + ";COBOL;Workshop;120;Beginner;\"Hopper; Grace\";SUBMITTED;" + date + ";;0"),
                lines[2]);
    }

    private long id(HttpResponse<String> res) throws Exception {
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
