package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Workshop registration (workflow/specs/workshop-registration.md): rules R1-R6 and decisions D1-D8. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopRegistrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.fresh(registry);
    }

    private static String database;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // R1, R6, D7, D8
    @Test
    void registersAndListsInOrder() throws Exception {
        long workshop = acceptedWorkshop();
        String grace = unique("Grace.Hopper"), alan = unique("alan"), ada = unique("ADA");
        JsonNode first = register(workshop, "Grace Hopper", grace, 201);
        assertTrue(first.get("id").isIntegralNumber(), first.toString());
        assertEquals(workshop, first.get("workshopId").asLong());
        assertEquals("Grace Hopper", first.get("name").asText());
        assertEquals(grace, first.get("email").asText());
        register(workshop, "Alan Turing", alan, 201);
        register(workshop, "Ada Lovelace", ada, 201);

        JsonNode list = list(workshop, 200);
        assertEquals(3, list.size());
        assertEquals(Arrays.asList(grace, alan, ada), emails(list));
        assertEquals(first.get("id").asLong(), list.get(0).get("id").asLong());
        assertTrue(list.get(0).get("id").asLong() < list.get(1).get("id").asLong());
    }

    // R2, D2, D3, D6
    @Test
    void onlyAcceptedWorkshopsTakeRegistrations() throws Exception {
        long submitted = workshop();
        register(submitted, "A", unique("a"), 409);
        assertEquals(0, list(submitted, 200).size());

        long waitlisted = workshop();
        assertEquals(200, send("PUT", "/talks/" + waitlisted + "/status", "{\"status\":\"WAITLISTED\"}").statusCode());
        register(waitlisted, "A", unique("a"), 409);
        assertEquals(0, list(waitlisted, 200).size());

        long rejected = workshop();
        assertEquals(200, send("PUT", "/talks/" + rejected + "/status", "{\"status\":\"REJECTED\"}").statusCode());
        register(rejected, "A", unique("a"), 409);
        assertEquals(0, list(rejected, 200).size());

        // An accepted talk that isn't a workshop: the talk exists, but only workshops take registrations,
        // so 409 like a workshop that isn't accepted (harness workshop-registration/check-2; lessons.md L8).
        long presentation = accept(talk(speaker(), "PRESENTATION"));
        register(presentation, "A", unique("a"), 409);
        list(presentation, 404);
        long lightning = accept(talk(speaker(), "LIGHTNING_10"));
        register(lightning, "A", unique("a"), 409);

        register(999_999_999L, "A", unique("a"), 404);
        list(999_999_999L, 404);
    }

    // R3
    @Test
    void withdrawnWorkshopKeepsItsList() throws Exception {
        long speaker = speaker();
        long workshop = accept(talk(speaker, "WORKSHOP"));
        register(workshop, "One", unique("one"), 201);
        register(workshop, "Two", unique("two"), 201);
        assertEquals(200, send("POST", "/talks/" + workshop + "/withdraw", "{\"speakerId\":" + speaker + "}").statusCode());
        register(workshop, "Three", unique("three"), 409);
        assertEquals(2, list(workshop, 200).size());
    }

    // R4, D4
    @Test
    void twentySeatsAndNoMore() throws Exception {
        long workshop = acceptedWorkshop();
        for (int i = 0; i < 20; i++) {
            register(workshop, "Attendee " + i, unique("a" + i), 201);
        }
        register(workshop, "Attendee 21", unique("late"), 409);
        assertEquals(20, list(workshop, 200).size());
    }

    // R4 under a rush. Several rounds: with the lock removed, a single round let 21+ through in only 2 runs of 3.
    @Test
    void rushNeverExceedsTwentySeats() throws Exception {
        for (int round = 0; round < 5; round++) {
            long workshop = acceptedWorkshop();
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                sent.add(async(workshop, "{\"name\":\"Rush " + i + "\",\"email\":\"" + unique("rush" + i) + "\"}"));
            }
            int created = 0, full = 0;
            for (CompletableFuture<HttpResponse<String>> future : sent) {
                HttpResponse<String> response = future.join();
                assertTrue(response.statusCode() == 201 || response.statusCode() == 409, response.statusCode() + " " + response.body());
                if (response.statusCode() == 201) created++; else full++;
            }
            assertEquals(20, created, "round " + round);
            assertEquals(20, full, "round " + round);
            assertEquals(20, list(workshop, 200).size(), "round " + round);
        }
    }

    // R5, D5, D7
    @Test
    void oneRegistrationPerEmailIgnoringCase() throws Exception {
        long workshop = acceptedWorkshop();
        String local = "ada-" + UUID.randomUUID();
        register(workshop, "Ada", local + "@example.com", 201);
        register(workshop, "Ada again", local + "@example.com", 409);
        register(workshop, "ADA", local.toUpperCase() + "@Example.COM", 409);
        JsonNode list = list(workshop, 200);
        assertEquals(1, list.size());
        assertEquals(local + "@example.com", list.get(0).get("email").asText());
    }

    // D5: a duplicate on a full workshop is still a 409 and changes nothing
    @Test
    void duplicateOnFullWorkshopIsRejected() throws Exception {
        long workshop = acceptedWorkshop();
        String first = unique("first");
        register(workshop, "First", first, 201);
        for (int i = 1; i < 20; i++) {
            register(workshop, "Attendee " + i, unique("a" + i), 201);
        }
        register(workshop, "First", first.toUpperCase(), 409);
        assertEquals(20, list(workshop, 200).size());
    }

    // D1: an ordinary address with a dotted domain, including subdomains, is accepted
    @Test
    void dottedDomainsAreAccepted() throws Exception {
        long workshop = acceptedWorkshop();
        register(workshop, "Sub", "sub-" + UUID.randomUUID() + "@mail.example.co.uk", 201);
    }

    // R5
    @Test
    void samePersonCanRegisterForSeveralWorkshops() throws Exception {
        String email = unique("busy");
        long one = acceptedWorkshop(), two = acceptedWorkshop();
        register(one, "Busy", email, 201);
        register(two, "Busy", email, 201);
    }

    // R5 under concurrency
    @Test
    void simultaneousDuplicatesRegisterOnce() throws Exception {
        long workshop = acceptedWorkshop();
        String local = "twin-" + UUID.randomUUID();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String email = (i % 2 == 0 ? local : local.toUpperCase()) + "@example.com";
            sent.add(async(workshop, "{\"name\":\"Twin\",\"email\":\"" + email + "\"}"));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> future : sent) {
            HttpResponse<String> response = future.join();
            assertTrue(response.statusCode() == 201 || response.statusCode() == 409, response.statusCode() + " " + response.body());
            if (response.statusCode() == 201) created++;
        }
        assertEquals(1, created);
        assertEquals(1, list(workshop, 200).size());
    }

    // R1, D1: bad input is 400 and stores nothing
    @Test
    void badInputIsRejectedAndChangesNothing() throws Exception {
        long workshop = acceptedWorkshop();
        String path = "/workshops/" + workshop + "/registrations";
        for (String body : Arrays.asList(
                "", "not json", "{}",
                "{\"email\":\"x@example.com\"}",
                "{\"name\":null,\"email\":\"x@example.com\"}",
                "{\"name\":\"  \",\"email\":\"x@example.com\"}",
                "{\"name\":\"X\"}",
                "{\"name\":\"X\",\"email\":null}",
                "{\"name\":\"X\",\"email\":\"\"}",
                "{\"name\":\"X\",\"email\":\"not-an-email\"}",
                "{\"name\":\"X\",\"email\":\" x@example.com\"}",
                // D1 (overridden in review): the domain needs a dot, so a@b is not an email
                "{\"name\":\"X\",\"email\":\"a@b\"}",
                "{\"name\":\"X\",\"email\":\"a@localhost\"}",
                // Wrong JSON types: Jackson would turn 123 or true into "123"/"true" (review finding E2)
                "{\"name\":123,\"email\":\"x@example.com\"}",
                "{\"name\":true,\"email\":\"x@example.com\"}",
                "{\"name\":\"X\",\"email\":42}",
                "{\"name\":[\"X\"],\"email\":\"x@example.com\"}",
                // A NUL character: Postgres refuses it, which was a 500 (review finding E1)
                "{\"name\":\"A\\u0000B\",\"email\":\"x@example.com\"}")) {
            HttpResponse<String> response = send("POST", path, body);
            assertEquals(400, response.statusCode(), body + " -> " + response.body());
        }
        assertEquals(400, send("POST", "/workshops/abc/registrations", "{\"name\":\"X\",\"email\":\"x@example.com\"}").statusCode());
        assertEquals(400, send("GET", "/workshops/abc/registrations", null).statusCode());
        assertEquals(0, list(workshop, 200).size());
    }

    // API table: the workshop row stays locked for 2 s -> 503 with Retry-After: 1, nothing stored (review finding S1)
    @Test
    void lockedWorkshopAnswers503AndChangesNothing() throws Exception {
        long workshop = acceptedWorkshop();
        try (Connection holder = TestDatabase.connect(database)) {
            CoSpeakerLocks.lockTalk(holder, workshop);
            HttpResponse<String> response = send("POST", "/workshops/" + workshop + "/registrations",
                    "{\"name\":\"Waiting\",\"email\":\"" + unique("waiting") + "\"}");
            assertEquals(503, response.statusCode(), response.body());
            assertEquals("1", response.headers().firstValue("Retry-After").orElse(null));
            holder.rollback();
        }
        assertEquals(0, list(workshop, 200).size());
    }

    // ---------------------------------------------------------------- helpers

    private long acceptedWorkshop() throws Exception {
        return accept(workshop());
    }

    private long workshop() throws Exception {
        return talk(speaker(), "WORKSHOP");
    }

    private long accept(long talk) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            HttpResponse<String> rated = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}");
            assertEquals(200, rated.statusCode(), rated.body());
        }
        HttpResponse<String> decided = send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}");
        assertEquals(200, decided.statusCode(), decided.body());
        return talk;
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Speaker\",\"email\":\"" + unique("speaker") + "\"}");
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speaker, String format) throws Exception {
        HttpResponse<String> res = send("POST", "/talks", "{\"title\":\"Hands on\",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private JsonNode register(long workshop, String name, String email, int status) throws Exception {
        String body = json.createObjectNode().put("name", name).put("email", email).toString();
        HttpResponse<String> res = send("POST", "/workshops/" + workshop + "/registrations", body);
        assertEquals(status, res.statusCode(), "register " + email + " -> " + res.body());
        return json.readTree(res.body());
    }

    private JsonNode list(long workshop, int status) throws Exception {
        HttpResponse<String> res = send("GET", "/workshops/" + workshop + "/registrations", null);
        assertEquals(status, res.statusCode(), res.body());
        return json.readTree(res.body());
    }

    private static List<String> emails(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(r -> out.add(r.get("email").asText()));
        return out;
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID() + "@example.com";
    }

    private CompletableFuture<HttpResponse<String>> async(long workshop, String body) {
        return http.sendAsync(request("POST", "/workshops/" + workshop + "/registrations", body),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
