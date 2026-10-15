package com.dating.social.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A match between two accounts (internal ids, never exposed by the API).
 *
 * @param closedAt null while the match is active; set by unmatch or block
 */
public record MatchInfo(UUID id, UUID accountLow, UUID accountHigh, Instant createdAt, Instant closedAt) {

    public boolean involves(UUID accountId) {
        return accountLow.equals(accountId) || accountHigh.equals(accountId);
    }

    public UUID other(UUID accountId) {
        if (accountLow.equals(accountId)) {
            return accountHigh;
        }
        if (accountHigh.equals(accountId)) {
            return accountLow;
        }
        throw new IllegalArgumentException("Account is not a participant of match " + id);
    }

    public boolean active() {
        return closedAt == null;
    }
}
