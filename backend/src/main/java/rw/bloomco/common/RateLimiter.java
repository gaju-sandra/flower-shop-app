package rw.bloomco.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fixed-window, per-IP rate limiting for sensitive endpoints (login, register, contact form). */
@Component
public class RateLimiter {

    private record Window(long start, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(HttpServletRequest req, String bucket, int limit, Duration window) {
        String key = bucket + ":" + req.getRemoteAddr();
        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, cur) ->
                cur == null || now - cur.start > window.toMillis() ? new Window(now, 1) : new Window(cur.start, cur.count + 1));
        if (w.count > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a few minutes and try again.");
        }
        if (windows.size() > 10_000) windows.entrySet().removeIf(e -> now - e.getValue().start > window.toMillis());
    }

    /** Test hook. */
    public void reset() {
        windows.clear();
    }
}
