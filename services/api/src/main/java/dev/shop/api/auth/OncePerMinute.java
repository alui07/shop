package dev.shop.api.auth;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lets the first event per key in each minute through, so a log line cannot be flooded by the traffic it reports. */
final class OncePerMinute {

    private static final long WINDOW_MILLIS = 60_000;

    private final Clock clock;
    private final Map<String, Long> lastAllowed = new ConcurrentHashMap<>();

    OncePerMinute(Clock clock) {
        this.clock = clock;
    }

    boolean tryAcquire(String key) {
        long now = clock.millis();
        boolean[] allowed = {false};
        lastAllowed.compute(key, (k, last) -> {
            if (last == null || now - last >= WINDOW_MILLIS) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        return allowed[0];
    }
}
