package com.dating.gacha.api;

import com.dating.gacha.domain.PityTrigger;
import com.dating.gacha.domain.PullOutcome;
import com.dating.gacha.domain.Rarity;

/**
 * One result of a pull.
 *
 * @param index         position inside the pull request (0-based)
 * @param reward        what was rolled; for DUPLICATE_SHARDS it was NOT granted, shards were granted instead
 * @param isNew         true when the reward was credited as is (new cosmetic, consumable, premium days)
 * @param shardsGranted shards for a duplicate, 0 otherwise
 * @param pityTrigger   EPIC_PITY / LEGENDARY_PITY when a guarantee decided the tier, FORCED for testing, else null
 */
public record PullResultDto(int index, String entryId, Rarity rarity, GachaRewardDto reward, boolean isNew,
                            PullOutcome outcome, int shardsGranted, PityTrigger pityTrigger) {
}
