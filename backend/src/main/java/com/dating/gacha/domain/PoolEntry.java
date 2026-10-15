package com.dating.gacha.domain;

import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.Reward;

/**
 * One possible result of a banner. The chance of an ordinary pull is {@code weight / total weight of the pool}.
 *
 * @param item     catalog row for ITEM entries (null for premium days)
 * @param quantity units of the item (cosmetics: 1)
 * @param days     premium days for PREMIUM_DAYS entries
 * @param featured shown in the banner preview (rate-up / headline items)
 */
public record PoolEntry(
        String entryId,
        PoolRewardType rewardType,
        CatalogItem item,
        Long quantity,
        Integer days,
        Rarity rarity,
        long weight,
        boolean featured,
        int sortOrder
) {

    public String itemCode() {
        return item == null ? null : item.code();
    }

    /** A cosmetic is owned once; a second copy is a duplicate and turns into shards. */
    public boolean ownable() {
        return rewardType == PoolRewardType.ITEM && !item.kind().stackable();
    }

    public Reward toReward() {
        return rewardType == PoolRewardType.ITEM
                ? new Reward.Item(item.code(), quantity)
                : new Reward.PremiumDays(days);
    }
}
