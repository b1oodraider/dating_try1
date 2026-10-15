package com.dating.gacha.domain;

/**
 * Guarantees of a banner. Counters are kept per account and pity group (see {@link PityState}).
 *
 * @param epicPity           an EPIC-or-better result is guaranteed on the N-th pull without one (null = off).
 *                           10 means every 10-pull contains at least one EPIC+.
 * @param legendaryPity      a LEGENDARY result is guaranteed on the M-th pull without one (null = off)
 * @param softPityStart      from this pull number without a LEGENDARY, the LEGENDARY tier weight grows (null = off)
 * @param softPityWeightStep weight added to the LEGENDARY tier per pull from {@code softPityStart} on,
 *                           in the same units as the pool weights
 */
public record PityRules(Integer epicPity, Integer legendaryPity, Integer softPityStart, long softPityWeightStep) {

    public static final PityRules NONE = new PityRules(null, null, null, 0);

    /**
     * @return true if the next pull (the {@code state}'s counters + 1) must be EPIC or better
     */
    public boolean epicDue(PityState state) {
        return epicPity != null && state.pullsSinceEpic() + 1 >= epicPity;
    }

    /**
     * @return true if the next pull must be LEGENDARY
     */
    public boolean legendaryDue(PityState state) {
        return legendaryPity != null && state.pullsSinceLegendary() + 1 >= legendaryPity;
    }

    /**
     * Extra LEGENDARY tier weight for the next pull: step × (pulls since soft pity started, counting the next one).
     */
    public long softPityBonus(PityState state) {
        if (softPityStart == null || softPityWeightStep <= 0) {
            return 0;
        }
        long steps = state.pullsSinceLegendary() + 1L - softPityStart + 1;
        return steps <= 0 ? 0 : Math.multiplyExact(steps, softPityWeightStep);
    }
}
