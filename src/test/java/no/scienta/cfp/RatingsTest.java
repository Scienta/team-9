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
 * Ratings: a reviewer has one 1-5 rating per talk, averages round half up to one decimal, and sort=rating ranks rated
 * talks first. Runs the real app on a random port with a throwaway database. Reads JSON with regexes rather than
 * Jackson, so it compiles unchanged across the Jackson 2 -> 3 move in Spring Boot 4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void scoreMustBeAWholeNumberFromOneToFive() throws Exception {
        long talk = talk(speaker());
        for (String body : new String[] {"{\"score\":0}", "{\"score\":6}", "{\"score\":4.5}",
                "{\"score\":\"4\"}", "{\"score\":null}", "{}", "{\"score\":true}"}) {
            assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/1", body).statusCode(), body);
        }
        assertEquals(0, Integer.parseInt(value(get("/talks/" + talk), "ratingCount")));
        for (int score = 1; score <= 5; score++) {
            assertEquals(200, rate(talk, score, score).statusCode());
        }
        assertEquals(5, Integer.parseInt(value(get("/talks/" + talk), "ratingCount")));
    }

    @Test
    void reviewerIdOutsideTheStoredRangeIsBadRequestNotServerError() throws Exception {
        long talk = talk(speaker());
        for (String reviewer : new String[] {"0", "-1", "2147483648", "9223372036854775807"}) {
            HttpResponse<String> res = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":3}");
            assertEquals(400, res.statusCode(), reviewer + ": " + res.body());
        }
        assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/abc", "{\"score\":3}").statusCode());
        assertEquals(200, rate(talk, 2147483647, 3).statusCode());
    }

    @Test
    void unknownTalkIsNotFound() throws Exception {
        assertEquals(404, rate(999999, 1, 3).statusCode());
        assertEquals(404, send("PUT", "/talks/9223372036854775807/ratings/1", "{\"score\":3}").statusCode());
    }

    @Test
    void aReviewerRatesATalkOnceAlsoWhenClicksArriveTogether() throws Exception {
        long talk = talk(speaker());
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(http.sendAsync(request("PUT", "/talks/" + talk + "/ratings/7", "{\"score\":" + (i % 5 + 1) + "}"),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            assertEquals(200, f.join().statusCode());
        }
        assertEquals(1, Integer.parseInt(value(get("/talks/" + talk), "ratingCount")));
    }

    @Test
    void averageRoundsToTwoDecimals() throws Exception {
        long talk = talk(speaker());
        rate(talk, 1, 4);
        rate(talk, 2, 4);
        rate(talk, 3, 4);
        rate(talk, 4, 5);
        String t = get("/talks/" + talk);
        assertEquals("4.25", value(t, "averageRating"), t); // 2 decimals since ratings-cr R3
        assertEquals("4", value(t, "ratingCount"));
        rate(talk, 4, 3); // 15/4 = 3.75
        assertEquals("3.75", value(get("/talks/" + talk), "averageRating"));
    }

    @Test
    void sortByRatingRanksByAverageThenCountThenIdWithUnratedLast() throws Exception {
        long a = speaker(), b = speaker();
        long unrated = talk(a);
        long fourOnce = talk(a);
        long fourTwice = talk(a);
        long five = talk(b);
        long alsoFourOnce = talk(b);
        rate(fourOnce, 1, 4);
        rate(fourTwice, 1, 4);
        rate(fourTwice, 2, 4);
        rate(five, 1, 5);
        rate(alsoFourOnce, 1, 4);

        // Other tests in this class share the database, so keep only this test's talks, in the order returned.
        List<Long> mine = List.of(unrated, fourOnce, fourTwice, five, alsoFourOnce);
        List<Long> order = new ArrayList<>();
        Matcher m = ID.matcher(get("/talks?sort=rating"));
        while (m.find()) {
            if (mine.contains(Long.parseLong(m.group(1)))) {
                order.add(Long.parseLong(m.group(1)));
            }
        }
        assertEquals(List.of(five, fourTwice, fourOnce, alsoFourOnce, unrated), order);
        assertEquals("null", value(get("/talks/" + unrated), "averageRating"));

        assertEquals(400, send("GET", "/talks?sort=title", null).statusCode());
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Reviewer Target\",\"email\":\"r-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(value(res.body(), "id"));
    }

    private long talk(long speaker) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(value(res.body(), "id"));
    }

    private HttpResponse<String> rate(long talk, long reviewer, int score) throws Exception {
        return send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":" + score + "}");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> res = send("GET", path, null);
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    /** The first value of a field that is a number or null. */
    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(null|-?[\\d.]+)").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1);
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
