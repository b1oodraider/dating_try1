package com.dating.gacha.api;

import com.dating.gacha.domain.PityRules;
import com.dating.gacha.domain.PityState;

/**
 * The caller's pity counters in a pity group (shared by all banners of the group).
 *
 * @param epicGuaranteedIn      the guaranteed EPIC+ comes at the latest on this pull from now (1 = the next one);
 *                              null if the banner has no such guarantee
 * @param legendaryGuaranteedIn same for LEGENDARY
 */
public record PityProgressDto(String pityGroup, int pullsSinceEpic, int pullsSinceLegendary, Integer epicGuaranteedIn,
                              Integer legendaryGuaranteedIn, long totalPulls) {

    public static PityProgressDto of(String pityGroup, PityRules rules, PityState state) {
        return new PityProgressDto(pityGroup, state.pullsSinceEpic(), state.pullsSinceLegendary(),
                remaining(rules.epicPity(), state.pullsSinceEpic()),
                remaining(rules.legendaryPity(), state.pullsSinceLegendary()),
                state.totalPulls());
    }

    private static Integer remaining(Integer pity, int since) {
        return pity == null ? null : Math.max(1, pity - since);
    }
}
