package com.dating.boost.api;

import com.dating.boost.domain.Boost;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The owner's view of boosts.
 *
 * @param startsAt        current boost start; null when no boost is active
 * @param endsAt          current boost end; null when no boost is active
 * @param impressions     impressions of the current boost; 0 when no boost is active
 * @param available       BOOST balance (bought + weekly premium allowance)
 * @param durationMinutes length of a new boost
 * @param now             "now" on the owner's clock (shifted for test accounts): use it for the countdown
 * @param history         last boosts including the current one, newest first
 */
public record BoostStatusDto(
        boolean active,
        Instant startsAt,
        Instant endsAt,
        long impressions,
        long available,
        long durationMinutes,
        Instant now,
        List<BoostRecordDto> history
) {
    public record BoostRecordDto(UUID id, Instant startsAt, Instant endsAt, long impressions) {
        public static BoostRecordDto from(Boost boost) {
            return new BoostRecordDto(boost.id(), boost.startsAt(), boost.endsAt(), boost.impressions());
        }
    }
}
