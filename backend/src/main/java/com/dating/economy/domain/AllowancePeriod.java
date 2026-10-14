package com.dating.economy.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;

/**
 * Period of a premium allowance. The period key makes the grant idempotent: one grant per key.
 * Periods are calendar-based in UTC (ISO weeks start on Monday).
 */
public enum AllowancePeriod {
    DAILY,
    WEEKLY,
    MONTHLY;

    public String keyFor(Instant now) {
        LocalDate date = now.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (this) {
            case DAILY -> date.toString();
            case WEEKLY -> String.format("%d-W%02d",
                    date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTHLY -> String.format("%d-%02d", date.getYear(), date.getMonthValue());
        };
    }
}
