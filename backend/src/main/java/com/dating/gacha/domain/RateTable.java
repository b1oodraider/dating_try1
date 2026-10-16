package com.dating.gacha.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Disclosed base rates of a pool (chance of one ordinary pull, before guarantees), derived from the weights.
 * <p>
 * Percentages have 4 decimal places and are rounded with the largest remainder method, so each list sums to
 * exactly 100.0000. The exact chance is always {@code weight / totalWeight}, which clients may show instead.
 *
 * @param rarityPercents every rarity tier (0 when the pool has none), in tier order
 * @param entryPercents  entry id → percent, in pool order
 */
public record RateTable(long totalWeight, Map<Rarity, Long> rarityWeights, Map<Rarity, BigDecimal> rarityPercents,
                        Map<String, BigDecimal> entryPercents) {

    static final int SCALE = 4;
    private static final long HUNDRED_PERCENT_UNITS = 100L * 10_000L; // 100 % in units of 0.0001 %

    public static RateTable of(List<PoolEntry> pool) {
        Map<Rarity, Long> rarityWeights = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            rarityWeights.put(rarity, 0L);
        }
        long total = 0;
        for (PoolEntry entry : pool) {
            rarityWeights.merge(entry.rarity(), entry.weight(), Math::addExact);
            total = Math.addExact(total, entry.weight());
        }

        Map<Rarity, BigDecimal> rarityPercents = new EnumMap<>(Rarity.class);
        Map<String, BigDecimal> entryPercents = new LinkedHashMap<>();
        if (total > 0) {
            List<Rarity> rarities = List.of(Rarity.values());
            List<BigDecimal> tierPercents = percentages(rarities.stream().map(rarityWeights::get).toList());
            for (int i = 0; i < rarities.size(); i++) {
                rarityPercents.put(rarities.get(i), tierPercents.get(i));
            }
            List<BigDecimal> perEntry = percentages(pool.stream().map(PoolEntry::weight).toList());
            for (int i = 0; i < pool.size(); i++) {
                entryPercents.put(pool.get(i).entryId(), perEntry.get(i));
            }
        } else {
            for (Rarity rarity : Rarity.values()) {
                rarityPercents.put(rarity, BigDecimal.ZERO.setScale(SCALE));
            }
        }
        return new RateTable(total, rarityWeights, rarityPercents, entryPercents);
    }

    /**
     * Largest remainder rounding of {@code weight / total × 100} to 4 decimals; the result sums to 100 exactly.
     */
    public static List<BigDecimal> percentages(List<Long> weights) {
        long total = weights.stream().mapToLong(Long::longValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("Total weight must be positive");
        }
        long[] units = new long[weights.size()];
        long[] remainders = new long[weights.size()];
        long assigned = 0;
        for (int i = 0; i < weights.size(); i++) {
            // weight <= 1e9 per entry and HUNDRED_PERCENT_UNITS = 1e6, so the product fits in a long
            long scaled = Math.multiplyExact(weights.get(i), HUNDRED_PERCENT_UNITS);
            units[i] = scaled / total;
            remainders[i] = scaled % total;
            assigned += units[i];
        }
        long left = HUNDRED_PERCENT_UNITS - assigned;
        List<Integer> order = new ArrayList<>(IntStream.range(0, weights.size()).boxed().toList());
        order.sort(Comparator.<Integer>comparingLong(i -> remainders[i]).reversed().thenComparing(i -> i));
        for (int k = 0; k < left; k++) {
            units[order.get(k)]++;
        }
        List<BigDecimal> result = new ArrayList<>(weights.size());
        for (long unit : units) {
            result.add(BigDecimal.valueOf(unit, SCALE));
        }
        return result;
    }
}
