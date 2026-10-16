package com.dating.boost.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One boost of a profile owner. Times are on the owner's clock (real time for real accounts,
 * shifted economy time for test accounts).
 *
 * @param impressions how many discovery pages served to other users included the profile during the boost
 */
public record Boost(UUID id, UUID accountId, Instant startsAt, Instant endsAt, long impressions, BoostSource source) {

    public boolean isActiveAt(Instant now) {
        return !startsAt.isAfter(now) && endsAt.isAfter(now);
    }
}
