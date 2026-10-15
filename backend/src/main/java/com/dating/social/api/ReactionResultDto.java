package com.dating.social.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Answer to like / super like / pass.
 *
 * @param matchId present when {@code matched}: the like was mutual (or the pair is already matched)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReactionResultDto(boolean matched, UUID matchId) {

    public static ReactionResultDto matched(UUID matchId) {
        return new ReactionResultDto(true, matchId);
    }

    public static ReactionResultDto notMatched() {
        return new ReactionResultDto(false, null);
    }
}
