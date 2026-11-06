package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * What users see that a framework upgrade (Spring Boot 2.7 -> 4, Spring 5 -> 7, Jackson 2 -> 3) tends to change
 * silently: trailing-slash URLs, JSON parsing and null fields, date formats, error bodies, charsets, status codes for
 * bad requests, and /actuator/info. Green on 2.7 and must stay green after the upgrade (spring-boot-4 spec: "nothing
 * else changes for our users").
 *
 * Deliberately uses no Jackson or javax/jakarta types, only strings and the JDK HTTP client, so it compiles unchanged
 * on both sides of the upgrade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpgradeContractTest {

    private static final String VERSION = "\\d+\\.\\d+\\.\\d+.*";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---------------------------------------------------------------- URLs

    // Spring 6 stopped matching a trailing slash by default; the speakers spec requires it for /speakers/ and
    // /speakers/{id}/.
    @Test
    void speakerUrlsWorkWithATrailingSlash() throws Exception {
        String email = "slash-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> created = send("POST", "/speakers/", "{\"name\":\"Slash\",\"email\":\"" + email + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = field(created.body(), "id");

        HttpResponse<String> read = send("GET", "/speakers/" + id + "/", null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(created.body(), read.body());

        HttpResponse<String> updated = send("PUT", "/speakers/" + id + "/", "{\"name\":\"Slashed\",\"email\":\"" + email + "\"}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"name\":\"Slashed\""), updated.body());

        assertEquals(200, send("GET", "/speakers/" + id + "/talks/", null).statusCode());
        assertEquals(409, send("POST", "/speakers/", "{\"name\":\"Again\",\"email\":\"" + email + "\"}").statusCode());
    }

    @Test
    void unknownRoutesAndMethodsKeepTheirStatusCodes() throws Exception {
        assertEquals(404, send("GET", "/no-such-route", null).statusCode());
        assertEquals(405, send("DELETE", "/speakers/1", null).statusCode());
        assertEquals(415, http.send(HttpRequest.newBuilder(uri("/speakers"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // ---------------------------------------------------------------- JSON in

    @Test
    void requestJsonMayHaveCommentsAndUnknownFieldsButCannotPickTheDuration() throws Exception {
        String speaker = speaker();
        HttpResponse<String> res = send("POST", "/talks",
                "{ /* the mobile client sends comments */ \"title\":\"T\",\"format\":\"LIGHTNING_10\",\"level\":\"BEGINNER\","
                        + "\"speakerId\":" + speaker + ",\"durationMinutes\":999,\"unknown\":{\"nested\":[1,2]}}");
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"durationMinutes\":10,"), res.body());
    }

    @Test
    void badJsonAndBadTypesAreBadRequest() throws Exception {
        String speaker = speaker();
        assertEquals(400, send("POST", "/talks", "{bad").statusCode());
        assertEquals(400, send("POST", "/talks", null).statusCode());
        assertEquals(400, send("POST", "/talks", "[]").statusCode());
        assertEquals(400, send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":\"abc\"}").statusCode());
        assertEquals(400, send("GET", "/talks/abc", null).statusCode());
        assertEquals(400, send("GET", "/talks?speakerId=abc", null).statusCode());
        assertEquals(400, send("GET", "/speakers/search", null).statusCode());
        String talk = talk(speaker);
        assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":\"4\"}").statusCode());
        assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4.5}").statusCode());
        assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/1", "{bad").statusCode());
    }

    // ---------------------------------------------------------------- JSON out

    @Test
    void responsesAreUtf8JsonWithNullFieldsIncluded() throws Exception {
        String email = "utf-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> res = send("POST", "/speakers", "{\"name\":\"Ærlig Østby Åsheim\",\"email\":\"" + email + "\"}");
        assertEquals(201, res.statusCode(), res.body());
        String type = res.headers().firstValue("Content-Type").orElse("");
        assertTrue(type.toLowerCase().startsWith("application/json"), type);
        assertTrue(type.toLowerCase().replace(" ", "").contains("charset=utf-8"), type);
        assertEquals("{\"id\":" + field(res.body(), "id") + ",\"name\":\"Ærlig Østby Åsheim\",\"email\":\"" + email
                + "\",\"bio\":null}", res.body());
    }

    @Test
    void talkJsonKeepsItsFieldsOrderAndFormats() throws Exception {
        String speaker = speaker();
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"Format check\",\"format\":\"WORKSHOP\",\"level\":\"ADVANCED\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        String id = field(res.body(), "id");
        String expected = "\\{\"id\":" + id + ",\"title\":\"Format check\",\"format\":\"WORKSHOP\",\"level\":\"ADVANCED\","
                + "\"speakerId\":" + speaker + ",\"coSpeakerIds\":\\[\\],\"durationMinutes\":120,\"status\":\"SUBMITTED\","
                + "\"submittedAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00\","
                + "\"submittedOn\":\"\\d{4}-\\d{2}-\\d{2}\",\"averageRating\":null,\"ratingCount\":0\\}";
        assertTrue(res.body().matches(expected), res.body());
        assertTrue(res.body().contains("\"submittedOn\":\"" + field(res.body(), "submittedAt").substring(0, 10) + "\""), res.body());

        assertEquals(res.body(), send("GET", "/talks/" + id, null).body());
    }

    @Test
    void ratingJsonAndAveragesKeepTheirFormats() throws Exception {
        String talk = talk(speaker());
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().matches("\\{\"talkId\":" + talk + ",\"reviewerId\":1,\"score\":4,"
                + "\"ratedAtDisplay\":\"\\d{1,2}/\\d{1,2}/\\d{2}, \\d{1,2}:\\d{2} [AP]M\"\\}"), res.body());
        send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":5}");
        String body = send("GET", "/talks/" + talk, null).body();
        assertTrue(body.contains("\"averageRating\":4.5,\"ratingCount\":2"), body);
        send("PUT", "/talks/" + talk + "/ratings/3", "{\"score\":5}");
        body = send("GET", "/talks/" + talk, null).body();
        assertTrue(body.contains("\"averageRating\":4.7,\"ratingCount\":3"), body);
    }

    // ---------------------------------------------------------------- errors

    // server.error.include-message and include-binding-errors are set for the mobile client.
    @Test
    void errorBodiesKeepTheirShape() throws Exception {
        HttpResponse<String> notFound = send("GET", "/talks/999999", null);
        assertEquals(404, notFound.statusCode());
        assertTrue(notFound.body().matches("\\{\"timestamp\":\"[^\"]+\",\"status\":404,\"error\":\"Not Found\","
                + "\"message\":\"talk not found\",\"path\":\"/talks/999999\"\\}"), notFound.body());

        HttpResponse<String> invalid = send("POST", "/speakers", "{\"name\":\"\",\"email\":\"not-an-email\"}");
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("\"status\":400"), invalid.body());
        assertTrue(invalid.body().contains("\"error\":\"Bad Request\""), invalid.body());
        assertTrue(invalid.body().contains("\"path\":\"/speakers\""), invalid.body());
        assertTrue(invalid.body().contains("\"errors\":["), invalid.body());
        assertTrue(invalid.body().contains("\"field\":\"name\""), invalid.body());
        assertTrue(invalid.body().contains("\"field\":\"email\""), invalid.body());

        HttpResponse<String> conflict = send("PUT", "/talks/" + talk(speaker()) + "/status", "{\"status\":\"ACCEPTED\"}");
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("\"message\":\"a talk needs ratings from 2 reviewers to be accepted\""),
                conflict.body());
    }

    // ---------------------------------------------------------------- admin

    @Test
    void adminCsvKeepsItsLayout() throws Exception {
        String speaker = speaker("Semi; \"Colon\"");
        String talk = talk(speaker);
        send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}");
        send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":5}");
        HttpResponse<String> res = send("GET", "/admin/talks.csv", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").toLowerCase().startsWith("text/csv"));
        assertEquals("attachment; filename=talks.csv", res.headers().firstValue("Content-Disposition").orElse(""));
        assertTrue(res.body().startsWith("id;title;format;minutes;level;speaker;status;submitted;rating;ratings\n"), res.body());
        assertTrue(res.body().matches("(?s).*\n" + talk + ";T;Presentation;45;Beginner;\"Semi; \"\"Colon\"\"\";SUBMITTED;"
                + "\\d{1,2}\\. \\p{L}+\\.? \\d{4};4,5;2\n.*"), res.body());
    }

    @Test
    void adminStatsKeepsItsFields() throws Exception {
        String body = send("GET", "/admin/stats", null).body();
        for (String key : new String[] {"speakers", "speakersWithTalks", "talks", "byFormat", "byLevel", "byStatus",
                "totalHours", "averageRating", "generated"}) {
            assertTrue(body.contains("\"" + key + "\":"), key + " in " + body);
        }
        assertTrue(body.matches(".*\"generated\":\"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\".*"), body);
    }

    // ---------------------------------------------------------------- actuator

    // The spring-boot-4 spec: /actuator/info must report the versions actually running, never hard-coded ones.
    @Test
    void actuatorInfoReportsTheRunningVersions() throws Exception {
        HttpResponse<String> res = send("GET", "/actuator/info", null);
        assertEquals(200, res.statusCode(), res.body());
        assertNotNull(SpringBootVersion.getVersion());
        assertEquals(SpringBootVersion.getVersion(), field(res.body(), "springBoot"), res.body());
        assertEquals(SpringVersion.getVersion(), field(res.body(), "springFramework"), res.body());
        assertEquals(System.getProperty("java.version"), field(res.body(), "java"), res.body());
        assertTrue(field(res.body(), "springBoot").matches(VERSION), res.body());
        assertTrue(field(res.body(), "springFramework").matches(VERSION), res.body());
        assertTrue(res.body().startsWith("{\"versions\":{"), res.body());
    }

    @Test
    void actuatorExposesOnlyHealthAndInfo() throws Exception {
        assertEquals(200, send("GET", "/actuator/health", null).statusCode());
        assertEquals(404, send("GET", "/actuator/env", null).statusCode());
        assertEquals(404, send("GET", "/actuator/beans", null).statusCode());
    }

    // ---------------------------------------------------------------- helpers

    private String speaker() throws Exception {
        return speaker("Upgrade Tester");
    }

    private String speaker(String name) throws Exception {
        String json = "{\"name\":\"" + name.replace("\"", "\\\"") + "\",\"email\":\"up-" + UUID.randomUUID() + "@example.com\"}";
        HttpResponse<String> res = send("POST", "/speakers", json);
        assertEquals(201, res.statusCode(), res.body());
        return field(res.body(), "id");
    }

    private String talk(String speaker) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return field(res.body(), "id");
    }

    /** The first value of a top-level-ish field: a number, or a string without escapes. */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(?:\"([^\"]*)\"|(-?[\\d.]+))").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
