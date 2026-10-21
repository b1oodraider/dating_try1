package com.dating.social.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Answer to like / super like / pass.
 *
 * @param matchId present when {@code matched}: the like was mutual (or the pair is already matched)
 * @param queued  true when the like waits for the liker's selfie verification (absent otherwise)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReactionResultDto(boolean matched, UUID matchId, Boolean queued) {

    public static ReactionResultDto matched(UUID matchId) {
        return new ReactionResultDto(true, matchId, null);
    }

    public static ReactionResultDto notMatched() {
        return new ReactionResultDto(false, null, null);
    }

    /** The liker has no verified selfie yet: the like is sent once it is approved. */
    public static ReactionResultDto queuedUntilVerified() {
        return new ReactionResultDto(false, null, true);
    }
}
