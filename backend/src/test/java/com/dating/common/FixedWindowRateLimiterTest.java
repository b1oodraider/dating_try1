package com.dating.common;

import com.dating.common.ratelimit.FixedWindowRateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedWindowRateLimiterTest {

    @Test
    void allowsUpToLimitThenReportsWaitUntilWindowResets() {
        MutableClock clock = new MutableClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofSeconds(60), clock);

        assertEquals(0, limiter.tryAcquire("k"));
        assertEquals(0, limiter.tryAcquire("k"));
        long wait = limiter.tryAcquire("k");
        assertTrue(wait > 0 && wait <= 60, "wait=" + wait);

        assertEquals(0, limiter.tryAcquire("other"), "keys are independent");

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, limiter.tryAcquire("k"), "new window");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
