package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.SpringVersion;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Spring Boot 4 upgrade (workflow/specs/spring-boot-4.md): the versions /actuator/info reports, and the 2.7
 * behaviour Boot 4 would otherwise have changed (trailing slashes, JSON comments, formats, error bodies).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpgradeTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // R1, R2, R3: the running versions, read from the classpath and the JVM, not hard-coded.
    @Test
    void infoReportsTheRunningVersions() throws Exception {
        HttpResponse<String> res = send("GET", "/actuator/info", null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(SpringBootVersion.getVersion().startsWith("4."), SpringBootVersion.getVersion());
        assertTrue(SpringVersion.getVersion().startsWith("7."), SpringVersion.getVersion());
        assertTrue(Runtime.version().feature() >= 17, Runtime.version().toString());
        assertEquals("{\"versions\":{\"java\":\"" + System.getProperty("java.version") + "\",\"springBoot\":\""
                + SpringBootVersion.getVersion() + "\",\"springFramework\":\"" + SpringVersion.getVersion() + "\"}}",
                res.body());
    }

    // R5: Boot 2.7 matched a trailing slash on every path; Spring 6+ answers 404 unless we strip it.
    @Test
    void trailingSlashes() throws Exception {
        HttpResponse<String> created = send("POST", "/speakers/", speaker("Slash"));
        assertEquals(201, created.statusCode(), created.body());
        String speaker = id(created);
        assertEquals(200, send("GET", "/speakers/" + speaker + "/", null).statusCode());
        HttpResponse<String> talk = send("POST", "/talks/", talk(speaker));
        assertEquals(201, talk.statusCode(), talk.body());
        String talkId = id(talk);
        assertEquals(200, send("GET", "/talks/" + talkId + "/", null).statusCode());
        assertEquals(200, send("GET", "/speakers/" + speaker + "/talks/", null).statusCode());
        assertEquals(200, send("GET", "/talks/", null).statusCode());
        assertEquals(200, send("PUT", "/talks/" + talkId + "/ratings/1/", "{\"score\":3}").statusCode());
        assertEquals(404, send("GET", "/speakers/999999/", null).statusCode());
    }

    // R6: the legacy formats, unchanged by Jackson 3 and JDK 25's locale data.
    @Test
    void formats() throws Exception {
        String speaker = id(send("POST", "/speakers", speaker("Formats")));
        HttpResponse<String> talk = send("POST", "/talks", talk(speaker));
        assertTrue(talk.body().matches(
                ".*\"submittedAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00\".*"), talk.body());
        assertTrue(talk.body().matches(".*\"submittedOn\":\"\\d{4}-\\d{2}-\\d{2}\".*"), talk.body());
        // Jackson 2's field order (declaration order), not Jackson 3's alphabetical one.
        assertTrue(talk.body().matches("\\{\"id\":\\d+,\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\","
                + "\"speakerId\":\\d+,\"coSpeakerIds\":\\[],\"durationMinutes\":45,\"status\":\"SUBMITTED\",.*"), talk.body());
        String talkId = id(talk);

        HttpResponse<String> rating = send("PUT", "/talks/" + talkId + "/ratings/1", "{\"score\":4}");
        // One ordinary space before AM/PM: JDK 20+ CLDR would put U+202F there in a localized time format.
        assertTrue(rating.body().matches(".*\"ratedAtDisplay\":\"\\d{1,2}/\\d{1,2}/\\d{2}, \\d{1,2}:\\d{2} [AP]M\".*"),
                rating.body());
        send("PUT", "/talks/" + talkId + "/ratings/2", "{\"score\":5}");
        assertTrue(send("GET", "/talks/" + talkId, null).body().contains("\"averageRating\":4.5,\"ratingCount\":2"));

        String csv = send("GET", "/admin/talks.csv", null).body();
        assertTrue(Pattern.compile(";\\d{1,2}\\. [a-z]{3}\\. \\d{4};4,5;2\\n").matcher(csv).find(), csv);
    }

    // R4: request and response JSON as on 2.7 (spring.jackson.* keys Boot 4 renamed, Jackson 2 defaults).
    @Test
    void jsonParsingAndWritingAsBefore() throws Exception {
        HttpResponse<String> comment = send("POST", "/speakers",
                "{\"name\":\"Comment\", /* legacy client */ \"email\":\"c-" + UUID.randomUUID() + "@example.com\"} // end");
        assertEquals(201, comment.statusCode(), comment.body());

        HttpResponse<String> emoji = send("POST", "/speakers",
                "{\"name\":\"Mic \uD83C\uDFA4\",\"email\":\"e-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, emoji.statusCode(), emoji.body());
        assertTrue(emoji.body().contains("\"name\":\"Mic \\uD83C\\uDFA4\""), emoji.body());

        assertEquals(400, send("PUT", "/talks/1/ratings/1", "{\"score\":4.0}").statusCode());
    }

    // R4, D5: errors keep Boot's body shape and the charset in the content type.
    @Test
    void errorBodiesAsBefore() throws Exception {
        HttpResponse<String> res = send("GET", "/speakers/999999", null);
        assertEquals(404, res.statusCode());
        assertEquals("application/json;charset=UTF-8", res.headers().firstValue("Content-Type").orElse(null));
        assertTrue(res.body().matches("\\{\"timestamp\":\"[^\"]+\",\"status\":404,\"error\":\"Not Found\","
                + "\"message\":\"speaker not found\",\"path\":\"/speakers/999999\"}"), res.body());

        HttpResponse<String> invalid = send("POST", "/speakers", "{\"name\":\"X\",\"email\":\"not-an-email\"}");
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("\"message\":\"Validation failed for object='speakerForm'. Error count: 1\""),
                invalid.body());
        assertTrue(invalid.body().contains("\"field\":\"email\""), invalid.body());

        assertEquals("{\"status\":\"UP\"}", send("GET", "/actuator/health", null).body());
        assertFalse(send("GET", "/actuator/env", null).statusCode() == 200);
    }

    private static String speaker(String name) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com\"}";
    }

    private static String talk(String speaker) {
        return "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}";
    }

    private static String id(HttpResponse<String> res) {
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
