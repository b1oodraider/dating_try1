package com.dating.social.application;

import java.util.UUID;

/**
 * Domain events of the social module. Published synchronously inside the business transaction;
 * listeners that talk to the outside world (real-time delivery) must act only after commit.
 */
public final class SocialEvents {

    private SocialEvents() {
    }

    /** A mutual like created a match (exactly once per active pair). */
    public record MatchCreated(UUID matchId, UUID accountA, UUID accountB) {
    }

    /** A match was closed by unmatch, block or account deletion; the chat disappears for both. */
    public record MatchClosed(UUID matchId, UUID accountA, UUID accountB) {
    }

    /** Someone liked the target (no match yet). Carries no liker identity: "who liked me" may be premium. */
    public record LikeReceived(UUID targetAccountId, boolean superlike) {
    }
}
