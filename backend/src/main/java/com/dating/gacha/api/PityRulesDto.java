package com.dating.gacha.api;

import com.dating.gacha.domain.PityRules;

/**
 * Guarantees of a banner, disclosed to users together with the rates.
 *
 * @param epicOrBetterWithin          an EPIC-or-better result is guaranteed within this many pulls (null = none)
 * @param legendaryWithin             a LEGENDARY result is guaranteed within this many pulls (null = none)
 * @param softPityStartsAt            from this pull without a LEGENDARY its chance grows (null = no soft pity)
 * @param softPityLegendaryWeightStep LEGENDARY tier weight added per pull of soft pity (pool weight units)
 */
public record PityRulesDto(Integer epicOrBetterWithin, Integer legendaryWithin, Integer softPityStartsAt,
                           long softPityLegendaryWeightStep) {

    public static PityRulesDto of(PityRules rules) {
        return new PityRulesDto(rules.epicPity(), rules.legendaryPity(), rules.softPityStart(),
                rules.softPityWeightStep());
    }
}
