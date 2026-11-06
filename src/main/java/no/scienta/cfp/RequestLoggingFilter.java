package no.scienta.cfp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs one line per request: method, path and query, status, duration, the request body and, for errors, the error
 * message. The harness's checks are otherwise invisible in the logs, and this is how we see what they send. /health is
 * skipped: the platform polls it constantly.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger("requests");
    private static final int MAX_BODY = 1000;
    private static final String ERROR_ATTRIBUTE = DefaultErrorAttributes.class.getName() + ".ERROR";

    private final RuntimeMetrics metrics;

    public RequestLoggingFilter(RuntimeMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // One byte over the limit, so body() can tell a truncated body from one of exactly MAX_BODY bytes.
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, MAX_BODY + 1);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            metrics.recordRequest(ms);
            int status = res.getStatus();
            String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
            String line = request.getMethod() + " " + request.getRequestURI() + query + " -> " + status + " (" + ms
                    + " ms) req=" + body(req.getContentAsByteArray());
            if (status >= 400) {
                // Spring renders the error body after this filter returns, so log the exception's message instead.
                Object error = request.getAttribute(ERROR_ATTRIBUTE);
                line += " res=" + (error instanceof Throwable ? ((Throwable) error).getMessage()
                        : body(res.getContentAsByteArray()));
            }
            LOG.info(line);
            res.copyBodyToResponse();
        }
    }

    private static String body(byte[] bytes) {
        if (bytes.length == 0) {
            return "-";
        }
        String s = new String(bytes, 0, Math.min(bytes.length, MAX_BODY), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        return bytes.length > MAX_BODY ? s + "…" : s;
    }
}
