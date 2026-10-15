package com.dating.gacha.api;

import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.ItemKind;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.domain.PoolRewardType;

import java.util.Map;

/**
 * What a pool entry grants.
 *
 * @param item     catalog item for ITEM rewards (null for PREMIUM_DAYS); rendered like wallet items
 * @param quantity units for ITEM rewards (cosmetics: 1)
 * @param days     premium days for PREMIUM_DAYS rewards
 */
public record GachaRewardDto(PoolRewardType type, Item item, Long quantity, Integer days) {

    public record Item(String code, ItemKind kind, String category, String title, String rarity, String equipSlot,
                       Map<String, Object> attributes) {
    }

    public static GachaRewardDto of(PoolEntry entry) {
        return of(entry.rewardType(), entry.item(), entry.quantity(), entry.days());
    }

    public static GachaRewardDto of(PoolRewardType type, CatalogItem item, Long quantity, Integer days) {
        Item itemDto = item == null ? null : new Item(item.code(), item.kind(), item.category(), item.title(),
                item.rarity(), item.equipSlot(), item.attributes());
        return new GachaRewardDto(type, itemDto, quantity, days);
    }
}
