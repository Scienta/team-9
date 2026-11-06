package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The metrics log line is how we tell CPU throttling from pool waits and GC/JIT in production (cpu-perf). It must
 * count every request that went through the filter, and say nothing in a quiet interval.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cfp.metrics.interval-seconds=3600") // the test drives report() itself
class RuntimeMetricsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    RuntimeMetrics metrics;

    private final HttpClient http = HttpClient.newHttpClient();

    private int get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void countsTheIntervalsRequestsThenGoesQuiet() throws Exception {
        metrics.report(); // drop whatever other tests' requests left behind
        assertEquals(404, get("/speakers/999999"));
        assertEquals(404, get("/talks/999999"));
        assertEquals(200, get("/health")); // not logged, not counted

        String line = metrics.report();
        assertNotNull(line, "an interval with requests must report");
        assertTrue(line.contains(" reqs=2 "), line);
        for (String field : new String[] {"p50=", "p95=", "max=", "gcMs=", "jitMs=", "poolActiveMax=", "poolWaitingMax=", "heapMb="}) {
            assertTrue(line.contains(field), field + " missing: " + line);
        }

        assertNull(metrics.report(), "a quiet interval must not log");
    }

    @Test
    void reportsWhatTheJvmIsGiven() {
        Map<String, Object> facts = metrics.staticFacts();
        assertEquals(Runtime.getRuntime().availableProcessors(), facts.get("availableProcessors"));
        assertEquals(5, facts.get("poolMax"));
        assertNotNull(facts.get("gc"));
        assertTrue(facts.containsKey("cpu.max"));
    }

    @Test
    void readsTheCgroupQuota() {
        assertEquals("0.500", RuntimeMetrics.quotaCpus("50000 100000"));
        assertEquals("1.120", RuntimeMetrics.quotaCpus("112000 100000"));
        assertEquals("max", RuntimeMetrics.quotaCpus("max 100000"));
        assertNull(RuntimeMetrics.quotaCpus(null));
        assertNull(RuntimeMetrics.quotaCpus("x y"));
    }

    @Test
    void percentiles() {
        assertEquals(0, RuntimeMetrics.percentileIndex(1, 50));
        assertEquals(0, RuntimeMetrics.percentileIndex(1, 95));
        assertEquals(49, RuntimeMetrics.percentileIndex(100, 50));
        assertEquals(94, RuntimeMetrics.percentileIndex(100, 95));
        assertEquals(1, RuntimeMetrics.percentileIndex(2, 95));
    }
}
