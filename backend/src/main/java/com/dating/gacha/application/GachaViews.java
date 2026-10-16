package com.dating.gacha.application;

import com.dating.gacha.api.BannerDto;
import com.dating.gacha.api.GachaRewardDto;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.domain.RateTable;

import java.util.List;

/**
 * Pool → disclosed-rates DTOs, shared by the user and the admin views.
 */
final class GachaViews {

    private GachaViews() {
    }

    static List<BannerDto.Entry> entries(List<PoolEntry> pool, RateTable rates) {
        return pool.stream()
                .map(e -> new BannerDto.Entry(e.entryId(), e.rarity(), GachaRewardDto.of(e), e.weight(),
                        rates.entryPercents().get(e.entryId()), e.featured()))
                .toList();
    }

    static BannerDto.Rates rates(RateTable rates) {
        return new BannerDto.Rates(rates.totalWeight(), rates.rarityPercents().entrySet().stream()
                .map(e -> new BannerDto.RarityRate(e.getKey(), rates.rarityWeights().get(e.getKey()), e.getValue()))
                .toList());
    }
}
