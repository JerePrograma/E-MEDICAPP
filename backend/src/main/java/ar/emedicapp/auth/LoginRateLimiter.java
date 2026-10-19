package ar.emedicapp.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;

@Component
public class LoginRateLimiter {
    private static final int MAX_KEYS = 5000;
    private static final long WINDOW_MILLIS = 300_000;
    private final Clock clock;
    private final LinkedHashMap<String, Window> attempts = new LinkedHashMap<>(16, 0.75f, true);

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean allow(String key) {
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().since >= WINDOW_MILLIS);
        var window = attempts.computeIfAbsent(key, ignored -> new Window(now));
        if (attempts.size() > MAX_KEYS) attempts.remove(attempts.keySet().iterator().next());
        return ++window.count <= 20;
    }

    private static final class Window {
        private final long since;
        private int count;

        private Window(long since) {
            this.since = since;
        }
    }
}
