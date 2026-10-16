package com.dating.safety.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * @param reason  a report reason (SPAM, SCAM, …) or a rule code (LIKE_SPAM, REVIEW_HOLD, MANUAL)
 * @param source  MODERATOR, RULE or ADMIN
 * @param endsAt  null = until lifted
 */
public record Restriction(
        UUID id,
        UUID accountId,
        RestrictionKind kind,
        String reason,
        String source,
        UUID reportId,
        UUID createdBy,
        String note,
        Instant startsAt,
        Instant endsAt,
        Instant liftedAt,
        UUID liftedBy,
        String liftNote
) {
    public boolean activeAt(Instant now) {
        return liftedAt == null && !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now));
    }
}
