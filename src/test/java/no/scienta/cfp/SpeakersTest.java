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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Speaker registration: emails are unique regardless of letter case, also under concurrent registrations, and the
 * old admin search matches names regardless of case. Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpeakersTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anEmailRegistersOnceRegardlessOfLetterCase() throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@example.com";
        assertEquals(201, register("Grace Hopper", email).statusCode());
        assertEquals(409, register("Grace Hopper", email.toUpperCase()).statusCode());
    }

    @Test
    void concurrentRegistrationsOfOneEmailHaveOneWinner() throws Exception {
        String email = "racer-" + UUID.randomUUID() + "@example.com";
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String variant = i % 2 == 0 ? email : email.toUpperCase();
            sent.add(http.sendAsync(request("POST", "/speakers", speaker("Racer " + i, variant)),
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
        assertEquals(1, created);
    }

    @Test
    void searchMatchesNamesRegardlessOfCase() throws Exception {
        String name = "Lovelace" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, register("Ada " + name, "ada-" + UUID.randomUUID() + "@example.com").statusCode());

        HttpResponse<String> found = send("GET", "/speakers/search?q=" + name.toLowerCase());
        assertEquals(200, found.statusCode());
        assertTrue(found.body().contains("\"name\":\"Ada " + name + "\""), found.body());
    }

    @Test
    void searchTreatsQueryAsDataNotSql() throws Exception {
        assertEquals(201, register("Injection Target", "target-" + UUID.randomUUID() + "@example.com").statusCode());
        HttpResponse<String> found = send("GET", "/speakers/search?q=%27%20OR%20%271%27%3D%271");
        assertEquals(200, found.statusCode(), found.body());
        assertEquals("[]", found.body());
    }

    @Test
    void updateValidatesLikeRegistration() throws Exception {
        HttpResponse<String> created = register("Ada", "ada-" + UUID.randomUUID() + "@example.com");
        String id = created.body().replaceAll(".*\"id\":(\\d+).*", "$1");
        String longBio = "x".repeat(501);
        HttpResponse<String> res = http.send(request("PUT", "/speakers/" + id,
                "{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"bio\":\"" + longBio + "\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode(), res.body());
        res = http.send(request("PUT", "/speakers/" + id, "{\"name\":\"  \",\"email\":\"ada@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode(), res.body());
    }

    @Test
    void registrationValidatesNameEmailAndBio() throws Exception {
        String ok = "v-" + UUID.randomUUID() + "@example.com";
        String[] bodies = {
                "{\"email\":\"" + ok + "\"}",
                "{\"name\":\" \",\"email\":\"" + ok + "\"}",
                "{\"name\":\"Ada\"}",
                "{\"name\":\"Ada\",\"email\":\"not-an-email\"}",
                "{\"name\":\"Ada\",\"email\":\"" + ok + "\",\"bio\":\"" + "x".repeat(501) + "\"}",
        };
        for (String body : bodies) {
            HttpResponse<String> res = http.send(request("POST", "/speakers", body), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, res.statusCode(), body + " -> " + res.body());
        }
        HttpResponse<String> res = http.send(request("POST", "/speakers",
                "{\"name\":\"Ada\",\"email\":\"" + ok + "\",\"bio\":\"" + "x".repeat(500) + "\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
    }

    @Test
    void unknownSpeakerIsNotFound() throws Exception {
        assertEquals(404, send("GET", "/speakers/999999").statusCode());
        assertEquals(404, http.send(request("PUT", "/speakers/999999", speaker("Ada", "ada-" + UUID.randomUUID() + "@example.com")),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void updateCannotTakeAnotherSpeakersEmailButMayRecaseItsOwn() throws Exception {
        String taken = "taken-" + UUID.randomUUID() + "@example.com";
        assertEquals(201, register("Taken", taken).statusCode());
        String mine = "mine-" + UUID.randomUUID() + "@example.com";
        String id = register("Mine", mine).body().replaceAll(".*\"id\":(\\d+).*", "$1");

        HttpResponse<String> res = http.send(request("PUT", "/speakers/" + id, speaker("Mine", taken.toUpperCase())),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, res.statusCode(), res.body());
        res = http.send(request("PUT", "/speakers/" + id, speaker("Mine Renamed", mine.toUpperCase())),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"name\":\"Mine Renamed\""), res.body());
        assertTrue(send("GET", "/speakers/" + id).body().contains(mine.toUpperCase()));
    }

    private HttpResponse<String> register(String name, String email) throws Exception {
        return http.send(request("POST", "/speakers", speaker(name, email)), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return http.send(request(method, path, null), HttpResponse.BodyHandlers.ofString());
    }

    private static String speaker(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
