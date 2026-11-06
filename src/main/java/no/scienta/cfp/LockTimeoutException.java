package no.scienta.cfp;

import java.sql.SQLException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A co-speaker mutation could not lock its talk within {@code lock_timeout} (Postgres SQLSTATE 55P03,
 * lock_not_available). Answered as 503 with {@code Retry-After: 1} through Spring Boot's normal error body.
 */
public class LockTimeoutException extends ResponseStatusException {

    static final String LOCK_NOT_AVAILABLE = "55P03";

    public LockTimeoutException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "talk is busy, retry shortly", cause);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        return headers;
    }

    /** True only if the cause chain holds an SQLException with SQLSTATE 55P03. */
    static boolean isLockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException && LOCK_NOT_AVAILABLE.equals(((SQLException) t).getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
