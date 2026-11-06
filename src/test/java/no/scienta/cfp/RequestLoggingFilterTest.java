package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

/** One log line per request: method, path and query, status, duration, request body, and for errors the error. */
class RequestLoggingFilterTest {

    private static final String ERROR_ATTRIBUTE = DefaultErrorAttributes.class.getName() + ".ERROR";
    private static final Pattern LINE = Pattern.compile("(\\S+ \\S+) -> (\\d+) \\((\\d+) ms\\) req=(.*?)( res=(.*))?");

    // RuntimeMetrics only counts request latencies here; a mock keeps this a plain unit test (no DataSource).
    private final RequestLoggingFilter filter = new RequestLoggingFilter(mock(RuntimeMetrics.class));

    @Test
    void logsMethodPathQueryStatusDurationAndBody() throws Exception {
        MockHttpServletRequest req = request("POST", "/talks", "{\n  \"title\":  \"T\"\n}");
        req.setQueryString("dry=1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Matcher line = only(run(req, res, 201, "{\"id\":1}", null));
        assertEquals("POST /talks?dry=1", line.group(1));
        assertEquals("201", line.group(2));
        long ms = Long.parseLong(line.group(3));
        assertTrue(ms >= 5 && ms < 5_000, "duration " + ms + " ms");
        assertEquals("{ \"title\": \"T\" }", line.group(4));
        assertEquals(null, line.group(5));
        assertEquals("{\"id\":1}", res.getContentAsString(), "the response body still reaches the client");
    }

    @Test
    void emptyBodyIsADash() throws Exception {
        assertEquals("-", only(run(request("GET", "/talks", null), new MockHttpServletResponse(), 200, "[]", null)).group(4));
    }

    @Test
    void bodiesOverTheLimitAreCutAndMarked() throws Exception {
        String exact = "a".repeat(1000);
        assertEquals(exact, only(run(request("POST", "/talks", exact), new MockHttpServletResponse(), 201, "", null)).group(4));
        String over = exact + "b";
        assertEquals(exact + "…", only(run(request("POST", "/talks", over), new MockHttpServletResponse(), 201, "", null)).group(4));
    }

    @Test
    void errorsLogTheExceptionMessageOrElseTheResponseBody() throws Exception {
        Matcher withException = only(run(request("GET", "/talks/1", null), new MockHttpServletResponse(), 404, "",
                new IllegalStateException("talk not found")));
        assertEquals("talk not found", withException.group(6));
        Matcher withBody = only(run(request("GET", "/talks/1", null), new MockHttpServletResponse(), 400, "bad sort", null));
        assertEquals("bad sort", withBody.group(6));
    }

    @Test
    void successesDoNotLogTheResponse() throws Exception {
        Matcher line = only(run(request("GET", "/talks", null), new MockHttpServletResponse(), 399, "body", null));
        assertEquals(null, line.group(5));
    }

    @Test
    void healthAndActuatorAreNotLogged() throws Exception {
        assertEquals(Collections.emptyList(), run(request("GET", "/health", null), new MockHttpServletResponse(), 200, "ok", null));
        assertEquals(Collections.emptyList(), run(request("GET", "/actuator/info", null), new MockHttpServletResponse(), 200, "{}", null));
        assertFalse(run(request("GET", "/healthy", null), new MockHttpServletResponse(), 200, "", null).isEmpty());
    }

    /** Runs the filter around a handler that reads the body, sleeps 5 ms and answers {@code status}. */
    private List<String> run(MockHttpServletRequest req, MockHttpServletResponse res, int status, String body,
                             Throwable error) throws Exception {
        FilterChain handler = (request, response) -> {
            StreamUtils.copyToByteArray(request.getInputStream());
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ((HttpServletResponse) response).setStatus(status);
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            if (error != null) {
                request.setAttribute(ERROR_ATTRIBUTE, error);
            }
        };
        try (LogCapture log = new LogCapture("requests")) {
            filter.doFilter(req, res, handler);
            return log.info();
        }
    }

    private static Matcher only(List<String> lines) {
        assertEquals(1, lines.size(), lines.toString());
        Matcher m = LINE.matcher(lines.get(0));
        assertTrue(m.matches(), lines.get(0));
        return m;
    }

    private static MockHttpServletRequest request(String method, String path, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (body != null) {
            req.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return req;
    }
}
