package com.dating.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window limiter. The app is a single-instance monolith (no Redis by design),
 * so a process-local limiter is enough; persistent limits are enforced separately in the DB.
 */
public class FixedWindowRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * @return 0 if the call is allowed, otherwise seconds until the window resets
     */
    public long tryAcquire(String key) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> now - w.startMillis >= windowMillis);
        }
        Window window = windows.compute(key, (k, current) -> {
            if (current == null || now - current.startMillis >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis, current.count + 1);
        });
        if (window.count <= limit) {
            return 0;
        }
        long remainingMillis = window.startMillis + windowMillis - now;
        return Math.max(1, (remainingMillis + 999) / 1000);
    }

    private record Window(long startMillis, int count) {
    }
}
