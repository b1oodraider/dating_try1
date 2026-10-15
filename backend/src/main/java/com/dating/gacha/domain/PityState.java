package com.dating.gacha.domain;

/**
 * Pity counters of one account in one pity group.
 *
 * @param pullsSinceEpic      pulls since the last EPIC-or-better result
 * @param pullsSinceLegendary pulls since the last LEGENDARY result
 * @param totalPulls          all pulls in the group (statistics)
 */
public record PityState(int pullsSinceEpic, int pullsSinceLegendary, long totalPulls) {

    public static final PityState ZERO = new PityState(0, 0, 0);

    /**
     * Counters after a result of the given rarity. Getting the rarity in any way (naturally or by a guarantee)
     * resets its counter; a LEGENDARY also counts as "EPIC or better".
     */
    public PityState after(Rarity rarity) {
        int sinceEpic = rarity.epicOrBetter() ? 0 : pullsSinceEpic + 1;
        int sinceLegendary = rarity == Rarity.LEGENDARY ? 0 : pullsSinceLegendary + 1;
        return new PityState(sinceEpic, sinceLegendary, totalPulls + 1);
    }
}
