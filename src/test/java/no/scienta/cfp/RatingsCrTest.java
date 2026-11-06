package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ratings change request (workflow/specs/ratings-cr.md): rules R1-R6 and decisions D1-D7. Reads JSON as text, not with
 * Jackson, as the other upgrade-safe tests do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsCrTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // R1, R3
    @Test
    void fewerThanFiveRatingsCountEveryScore() throws Exception {
        assertEquals("3.25", average(rated(1, 2, 5, 5)));       // 13 / 4, nothing left out
        assertEquals("4.33", average(rated(4, 4, 5)));          // 13 / 3 = 4.333...
        assertEquals("4.25", average(rated(4, 4, 4, 5)));       // the ratings spec's example, now 2 decimals
        assertEquals("1.0", average(rated(1)));
    }

    // R2, R4
    @Test
    void fiveOrMoreRatingsDropOneHighestAndOneLowest() throws Exception {
        long five = rated(1, 2, 2, 4, 5);                       // (2 + 2 + 4) / 3; 2.8 untrimmed
        assertEquals("2.67", average(five));
        assertEquals("5", field(get("/talks/" + five), "ratingCount"));
        long six = rated(1, 3, 3, 4, 5, 5);                     // (3 + 3 + 4 + 5) / 4
        assertEquals("3.75", average(six));
        assertEquals("6", field(get("/talks/" + six), "ratingCount"));
    }

    // R2: exactly one of each, even when several reviewers gave the same top or bottom score
    @Test
    void tiedExtremesDropOnlyOneEach() throws Exception {
        assertEquals("3.67", average(rated(5, 5, 5, 1, 1)));    // (5 + 5 + 1) / 3 = 3.666...
        assertEquals("5.0", average(rated(5, 5, 5, 5, 5)));
        assertEquals("2.5", average(rated(1, 1, 1, 4, 4, 4)));  // one 1 and one 4 left out: (1 + 1 + 4 + 4) / 4
    }

    // R3, D4: ties at the third decimal go to the even neighbour, on the exact fraction
    @Test
    void roundsToTwoDecimalsHalfEven() throws Exception {
        // 10 ratings: a 1 and a 5 are left out, the other 8 sum to 25 -> 3.125 -> 3.12
        assertEquals("3.12", average(rated(1, 5, 3, 3, 3, 3, 3, 3, 3, 4)));
        // the other 8 sum to 27 -> 3.375 -> 3.38
        assertEquals("3.38", average(rated(1, 5, 3, 3, 3, 3, 3, 4, 4, 4)));
        // the other 8 sum to 29 -> 3.625 -> 3.62
        assertEquals("3.62", average(rated(1, 5, 3, 3, 3, 4, 4, 4, 4, 4)));
    }

    // R5, D5: ranked by the trimmed, rounded average; same average -> more reviewers -> oldest
    @Test
    void rankingUsesTheTrimmedAverage() throws Exception {
        long speaker = speaker();
        long trimmedToFive = talk(speaker);   // 1,5,5,5,5: 4.2 untrimmed, 5.0 trimmed
        long fiveOfFour = talk(speaker);      // 5,5,5,5: 5.0, fewer reviewers
        long fourAndAHalf = talk(speaker);    // 4,5: 4.5
        rate(trimmedToFive, 1, 5, 5, 5, 5);
        rate(fiveOfFour, 5, 5, 5, 5);
        rate(fourAndAHalf, 4, 5);
        assertEquals(List.of(trimmedToFive, fiveOfFour, fourAndAHalf), ids(get("/talks?sort=rating&speakerId=" + speaker)));
    }

    // D6: replacing a rating moves which scores are left out
    @Test
    void replacingARatingMovesTheTrim() throws Exception {
        long talk = rated(1, 2, 3, 4, 5);
        assertEquals("3.0", average(talk));
        rate(talk, 5, 1);                                       // reviewer 5 now gives 1: 1,2,3,4,1 -> (1 + 2 + 3) / 3
        assertEquals("2.0", average(talk));
        assertEquals("5", field(get("/talks/" + talk), "ratingCount"));
    }

    // R6, D1: no ratings is null; numbers are written without trailing zeros
    @Test
    void unratedHasNoAverageAndNumbersHaveNoTrailingZeros() throws Exception {
        long unrated = talk(speaker());
        assertTrue(get("/talks/" + unrated).contains("\"averageRating\":null,\"ratingCount\":0"));
        String body = get("/talks/" + rated(4, 5));
        assertTrue(body.contains("\"averageRating\":4.5,"), body);
    }

    // R4, R5, every Talk response: the list endpoints show the same trimmed average
    @Test
    void everyTalkResponseShowsTheTrimmedAverage() throws Exception {
        long speaker = speaker();
        long talk = talk(speaker);
        rate(talk, 1, 1, 2, 3, 5);                              // (1 + 2 + 3) / 3; 2.4 untrimmed
        assertTrue(get("/speakers/" + speaker + "/talks").contains("\"averageRating\":2.0,\"ratingCount\":5"));
        assertTrue(get("/talks?speakerId=" + speaker).contains("\"averageRating\":2.0,\"ratingCount\":5"));
    }

    // D3: the CSV column follows the new average, decimal comma
    @Test
    void csvShowsTheNewAverage() throws Exception {
        long talk = rated(4, 4, 4, 5);
        String csv = get("/admin/talks.csv");
        assertTrue(csv.matches("(?s).*\n" + talk + ";[^\n]*;4,25;4\n.*"), csv);
        long trimmed = rated(1, 1, 2, 3, 5);
        assertTrue(get("/admin/talks.csv").matches("(?s).*\n" + trimmed + ";[^\n]*;2,0;5\n.*"));
    }

    // D2: /admin/stats averages the new per-talk averages, still rounded to 1 decimal half up
    @Test
    void statsAverageTheNewTalkAverages() throws Exception {
        rated(1, 2, 3, 4, 5);
        rated(4, 4, 4, 5);
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        Matcher m = Pattern.compile("\"averageRating\":(\\d+\\.\\d+)").matcher(get("/talks"));
        while (m.find()) {
            sum = sum.add(new BigDecimal(m.group(1)));
            n++;
        }
        String expected = sum.divide(BigDecimal.valueOf(n), 10, RoundingMode.HALF_UP).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        String stats = get("/admin/stats");
        String actual = new BigDecimal(field(stats, "averageRating")).stripTrailingZeros().toPlainString();
        assertEquals(expected, actual, stats);
    }

    // ---------------------------------------------------------------- helpers

    /** A new talk of its own speaker, rated by reviewers 1, 2, ... with these scores. */
    private long rated(int... scores) throws Exception {
        long talk = talk(speaker());
        rate(talk, scores);
        return talk;
    }

    private void rate(long talk, int... scores) throws Exception {
        for (int i = 0; i < scores.length; i++) {
            rate(talk, i + 1, scores[i]);
        }
    }

    private void rate(long talk, int reviewer, int score) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":" + score + "}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private String average(long talk) throws Exception {
        return field(get("/talks/" + talk), "averageRating");
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Rated\",\"email\":\"rated-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(field(res.body(), "id"));
    }

    private long talk(long speaker) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(field(res.body(), "id"));
    }

    private String get(String path) throws Exception {
        HttpResponse<String> res = send("GET", path, null);
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    private static List<Long> ids(String json) {
        List<Long> ids = new ArrayList<>();
        Matcher m = ID.matcher(json);
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    /** The first value of a field that is a number or null. */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(null|-?[\\d.]+)").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
