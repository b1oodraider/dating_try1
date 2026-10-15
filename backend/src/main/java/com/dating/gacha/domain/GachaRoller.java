package com.dating.gacha.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;

/**
 * The roll itself: pure and deterministic for a given random generator, so guarantees can be unit-tested
 * with a seeded generator while production uses {@link java.security.SecureRandom}.
 * <p>
 * A roll has two stages: first the rarity tier (weights summed per tier), then the entry inside the tier.
 * Without pity this gives exactly {@code entry weight / pool weight}, the disclosed rate. Guarantees narrow the
 * candidates (LEGENDARY only, or EPIC+ only) keeping the relative weights; soft pity adds weight to the
 * LEGENDARY tier. A pity rule whose tier is missing from the pool is ignored.
 */
public final class GachaRoller {

    /**
     * @param trigger    why the candidates were restricted, null for an ordinary roll
     * @param stateAfter pity counters after this result
     */
    public record Roll(PoolEntry entry, PityTrigger trigger, PityState stateAfter) {
    }

    private GachaRoller() {
    }

    /**
     * @param pool   entries in a stable order (the repository sorts them), must not be empty
     * @param forced testing: return this entry instead of rolling (counters still advance), null normally
     */
    public static Roll roll(List<PoolEntry> pool, PityRules rules, PityState state, RandomGenerator random,
                            PoolEntry forced) {
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("Cannot roll an empty pool");
        }
        if (forced != null) {
            return new Roll(forced, PityTrigger.FORCED, state.after(forced.rarity()));
        }

        PityTrigger trigger = null;
        List<PoolEntry> candidates = pool;
        if (rules.legendaryDue(state)) {
            List<PoolEntry> legendary = filter(pool, e -> e.rarity() == Rarity.LEGENDARY);
            if (!legendary.isEmpty()) {
                candidates = legendary;
                trigger = PityTrigger.LEGENDARY_PITY;
            }
        }
        if (trigger == null && rules.epicDue(state)) {
            List<PoolEntry> epicOrBetter = filter(pool, e -> e.rarity().epicOrBetter());
            if (!epicOrBetter.isEmpty()) {
                candidates = epicOrBetter;
                trigger = PityTrigger.EPIC_PITY;
            }
        }

        Map<Rarity, Long> tierWeights = new EnumMap<>(Rarity.class);
        for (PoolEntry entry : candidates) {
            tierWeights.merge(entry.rarity(), entry.weight(), Math::addExact);
        }
        long bonus = rules.softPityBonus(state);
        if (bonus > 0 && tierWeights.containsKey(Rarity.LEGENDARY)) {
            tierWeights.merge(Rarity.LEGENDARY, bonus, Math::addExact);
        }
        Rarity tier = pickWeighted(new ArrayList<>(tierWeights.keySet()), tierWeights::get, random);
        PoolEntry entry = pickWeighted(filter(candidates, e -> e.rarity() == tier), PoolEntry::weight, random);
        return new Roll(entry, trigger, state.after(entry.rarity()));
    }

    static <T> T pickWeighted(List<T> items, ToLongFunction<T> weight, RandomGenerator random) {
        long total = 0;
        for (T item : items) {
            total = Math.addExact(total, weight.applyAsLong(item));
        }
        if (total <= 0) {
            throw new IllegalArgumentException("Total weight must be positive");
        }
        long ticket = random.nextLong(total);
        for (T item : items) {
            ticket -= weight.applyAsLong(item);
            if (ticket < 0) {
                return item;
            }
        }
        throw new IllegalStateException("Weighted pick fell through");
    }

    private static List<PoolEntry> filter(List<PoolEntry> pool, Predicate<PoolEntry> predicate) {
        return pool.stream().filter(predicate).toList();
    }
}
