package com.dating.gacha.api;

import com.dating.gacha.domain.PityTrigger;
import com.dating.gacha.domain.PullOutcome;
import com.dating.gacha.domain.Rarity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of the caller's pull history, newest first.
 *
 * @param nextBefore pass as {@code before} to get the next (older) page; null when there is nothing more
 */
public record HistoryPageDto(List<Item> items, Long nextBefore) {

    /**
     * @param requestId the pull request (one 1-pull or multi-pull) this result belongs to
     * @param index     position inside the request
     */
    public record Item(UUID id, UUID requestId, String bannerCode, String bannerTitle, int index, String entryId,
                       Rarity rarity, GachaRewardDto reward, boolean isNew, PullOutcome outcome, int shardsGranted,
                       PityTrigger pityTrigger, Instant createdAt) {
    }
}
