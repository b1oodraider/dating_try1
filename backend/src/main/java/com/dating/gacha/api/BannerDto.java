package com.dating.gacha.api;

import com.dating.gacha.domain.BannerType;
import com.dating.gacha.domain.Rarity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A banner as shown to users.
 *
 * @param startsAt   null for permanent banners without a window
 * @param endsAt     null for permanent banners without a window
 * @param open       pulls are possible right now (in the caller's economy time)
 * @param attributes free-form rendering data (accent colors, art keys); unknown keys must be ignored
 * @param myPity     the caller's counters in the banner's pity group
 * @param rates      base chances of one ordinary pull (before guarantees), by rarity
 * @param featured   headline entries for the preview (or the rarest ones when none is marked)
 * @param pool       every entry with its chance; only in GET /gacha/banners/{code}
 */
public record BannerDto(String code, String title, String description, BannerType type, Instant startsAt,
                        Instant endsAt, boolean open, Map<String, Object> attributes, Cost cost,
                        PityRulesDto pityRules, PityProgressDto myPity, Rates rates, List<Entry> featured,
                        @JsonInclude(JsonInclude.Include.NON_NULL) List<Entry> pool) {

    /**
     * @param multiPullCost price of one multi-pull (perPull × multiPullSize, no discount)
     */
    public record Cost(String itemCode, int perPull, int multiPullSize, long multiPullCost) {
    }

    /**
     * @param totalWeight the exact chance of an entry is weight / totalWeight
     */
    public record Rates(long totalWeight, List<RarityRate> rarities) {
    }

    /**
     * @param percent 4 decimals; the list sums to exactly 100
     */
    public record RarityRate(Rarity rarity, long weight, BigDecimal percent) {
    }

    /**
     * @param percent 4 decimals; the whole pool sums to exactly 100
     */
    public record Entry(String entryId, Rarity rarity, GachaRewardDto reward, long weight, BigDecimal percent,
                        boolean featured) {
    }
}
