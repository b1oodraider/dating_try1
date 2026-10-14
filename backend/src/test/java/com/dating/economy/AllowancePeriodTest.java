package com.dating.economy;

import com.dating.economy.domain.AllowancePeriod;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AllowancePeriodTest {

    @Test
    void periodKeysAreUtcCalendarBased() {
        Instant sunday = Instant.parse("2026-10-18T23:59:59Z");
        Instant monday = Instant.parse("2026-10-19T00:00:00Z");

        assertEquals("2026-W42", AllowancePeriod.WEEKLY.keyFor(sunday));
        assertEquals("2026-W43", AllowancePeriod.WEEKLY.keyFor(monday), "ISO weeks start on Monday");
        assertEquals("2026-10-19", AllowancePeriod.DAILY.keyFor(monday));
        assertEquals("2026-10", AllowancePeriod.MONTHLY.keyFor(monday));
        assertEquals("2026-W53", AllowancePeriod.WEEKLY.keyFor(Instant.parse("2026-12-31T12:00:00Z")));
    }
}
