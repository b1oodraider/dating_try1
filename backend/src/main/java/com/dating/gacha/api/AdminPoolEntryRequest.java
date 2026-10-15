package com.dating.gacha.api;

import com.dating.gacha.domain.PoolRewardType;
import com.dating.gacha.domain.Rarity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Create or replace one pool entry (PUT /admin/gacha/banners/{code}/pool/{entryId}).
 *
 * @param itemCode ITEM only: catalog item code
 * @param quantity ITEM only, default 1 (cosmetics must be 1)
 * @param days     PREMIUM_DAYS only: 1..3650
 * @param rarity   required, except for cosmetics with a catalog rarity (taken from the catalog; must match if given)
 * @param weight   relative chance; the disclosed rate is weight / sum of the pool's weights
 */
public record AdminPoolEntryRequest(
        @NotNull(message = "Укажите тип награды") PoolRewardType rewardType,
        String itemCode,
        @Min(value = 1, message = "От 1 до 100000") @Max(value = 100_000, message = "От 1 до 100000") Long quantity,
        @Min(value = 1, message = "От 1 до 3650") @Max(value = 3650, message = "От 1 до 3650") Integer days,
        Rarity rarity,
        @NotNull(message = "Укажите вес")
        @Min(value = 1, message = "От 1 до 1000000000") @Max(value = 1_000_000_000, message = "От 1 до 1000000000") Long weight,
        Boolean featured,
        Integer sortOrder
) {
}
