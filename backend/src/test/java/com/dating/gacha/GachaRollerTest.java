package com.dating.gacha;

import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.ItemKind;
import com.dating.gacha.domain.GachaRoller;
import com.dating.gacha.domain.PityRules;
import com.dating.gacha.domain.PityState;
import com.dating.gacha.domain.PityTrigger;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.domain.PoolRewardType;
import com.dating.gacha.domain.RateTable;
import com.dating.gacha.domain.Rarity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The roll logic with seeded generators: rates, guarantees and soft pity are deterministic here.
 */
class GachaRollerTest {

    /** Same tiers as the seeded STANDARD banner: 70 / 24 / 5 / 1 %. */
    private static final List<PoolEntry> POOL = List.of(
            entry("c1", Rarity.COMMON, 3500), entry("c2", Rarity.COMMON, 3500),
            entry("r1", Rarity.RARE, 1400), entry("r2", Rarity.RARE, 1000),
            entry("e1", Rarity.EPIC, 300), entry("e2", Rarity.EPIC, 200),
            entry("l1", Rarity.LEGENDARY, 60), entry("l2", Rarity.LEGENDARY, 40));
    private static final PityRules DEFAULT_PITY = new PityRules(10, 80, null, 0);

    @Test
    void disclosedRatesSumToExactly100() {
        RateTable rates = RateTable.of(POOL);
        assertEquals(10_000, rates.totalWeight());
        assertEquals(new BigDecimal("70.0000"), rates.rarityPercents().get(Rarity.COMMON));
        assertEquals(new BigDecimal("1.0000"), rates.rarityPercents().get(Rarity.LEGENDARY));
        assertEquals(new BigDecimal("100.0000"), sum(rates.rarityPercents().values()));
        assertEquals(new BigDecimal("100.0000"), sum(rates.entryPercents().values()));

        // Weights that do not divide evenly are rounded with the largest remainder method.
        List<BigDecimal> thirds = RateTable.percentages(List.of(1L, 1L, 1L));
        assertEquals(List.of(new BigDecimal("33.3334"), new BigDecimal("33.3333"), new BigDecimal("33.3333")), thirds);
        assertEquals(new BigDecimal("100.0000"), sum(RateTable.percentages(List.of(7L, 13L, 1_000_000_000L, 3L))));
    }

    @Test
    void ordinaryRollsFollowTheDisclosedRates() {
        RandomGenerator random = new Random(42);
        Map<Rarity, Integer> counts = new EnumMap<>(Rarity.class);
        int pulls = 200_000;
        PityState state = PityState.ZERO;
        for (int i = 0; i < pulls; i++) {
            GachaRoller.Roll roll = GachaRoller.roll(POOL, PityRules.NONE, state, random, null);
            assertNull(roll.trigger());
            counts.merge(roll.entry().rarity(), 1, Integer::sum);
            state = roll.stateAfter();
        }
        assertEquals(0.70, counts.get(Rarity.COMMON) / (double) pulls, 0.005);
        assertEquals(0.24, counts.get(Rarity.RARE) / (double) pulls, 0.005);
        assertEquals(0.05, counts.get(Rarity.EPIC) / (double) pulls, 0.003);
        assertEquals(0.01, counts.get(Rarity.LEGENDARY) / (double) pulls, 0.002);
        assertEquals(pulls, state.totalPulls());
    }

    @Test
    void sameSeedGivesTheSameResults() {
        assertEquals(sequence(new Random(7), 50), sequence(new Random(7), 50));
    }

    @Test
    void guaranteesAreNeverExceeded() {
        for (long seed = 1; seed <= 5; seed++) {
            RandomGenerator random = new Random(seed);
            PityState state = PityState.ZERO;
            int sinceEpic = 0;
            int sinceLegendary = 0;
            int maxSinceEpic = 0;
            int maxSinceLegendary = 0;
            for (int i = 0; i < 20_000; i++) {
                GachaRoller.Roll roll = GachaRoller.roll(POOL, DEFAULT_PITY, state, random, null);
                state = roll.stateAfter();
                Rarity rarity = roll.entry().rarity();
                sinceEpic = rarity.epicOrBetter() ? 0 : sinceEpic + 1;
                sinceLegendary = rarity == Rarity.LEGENDARY ? 0 : sinceLegendary + 1;
                maxSinceEpic = Math.max(maxSinceEpic, sinceEpic);
                maxSinceLegendary = Math.max(maxSinceLegendary, sinceLegendary);
                assertEquals(sinceEpic, state.pullsSinceEpic());
                assertEquals(sinceLegendary, state.pullsSinceLegendary());
            }
            assertTrue(maxSinceEpic <= 9, "EPIC+ must come within 10 pulls, gap was " + maxSinceEpic);
            assertTrue(maxSinceLegendary <= 79, "LEGENDARY must come at 80, gap was " + maxSinceLegendary);
        }
    }

    @Test
    void guaranteeFiresExactlyAtTheThreshold() {
        for (long seed = 1; seed <= 200; seed++) {
            RandomGenerator random = new Random(seed);

            GachaRoller.Roll epic = GachaRoller.roll(POOL, DEFAULT_PITY, new PityState(9, 9, 9), random, null);
            assertEquals(PityTrigger.EPIC_PITY, epic.trigger());
            assertTrue(epic.entry().rarity().epicOrBetter());
            assertEquals(0, epic.stateAfter().pullsSinceEpic());

            GachaRoller.Roll legendary = GachaRoller.roll(POOL, DEFAULT_PITY, new PityState(3, 79, 79), random, null);
            assertEquals(PityTrigger.LEGENDARY_PITY, legendary.trigger());
            assertEquals(Rarity.LEGENDARY, legendary.entry().rarity());
            assertEquals(new PityState(0, 0, 80), legendary.stateAfter());

            GachaRoller.Roll before = GachaRoller.roll(POOL, DEFAULT_PITY, new PityState(8, 78, 78), random, null);
            assertNull(before.trigger(), "no guarantee one pull before the threshold");
        }
    }

    @Test
    void epicGuaranteeKeepsRelativeWeights() {
        RandomGenerator random = new Random(3);
        Map<Rarity, Integer> counts = new EnumMap<>(Rarity.class);
        for (int i = 0; i < 60_000; i++) {
            GachaRoller.Roll roll = GachaRoller.roll(POOL, DEFAULT_PITY, new PityState(9, 0, 0), random, null);
            counts.merge(roll.entry().rarity(), 1, Integer::sum);
        }
        // EPIC 500 vs LEGENDARY 100 → 5/6 vs 1/6.
        assertEquals(1 / 6.0, counts.get(Rarity.LEGENDARY) / 60_000.0, 0.01);
        assertNull(counts.get(Rarity.COMMON));
    }

    @Test
    void softPityRaisesTheLegendaryChance() {
        PityRules soft = new PityRules(null, null, 50, 1_000); // +10 % of the pool weight per pull from pull 50
        int legendaryBefore = 0;
        int legendaryAfter = 0;
        RandomGenerator random = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            if (GachaRoller.roll(POOL, soft, new PityState(0, 10, 0), random, null).entry().rarity() == Rarity.LEGENDARY) {
                legendaryBefore++;
            }
            if (GachaRoller.roll(POOL, soft, new PityState(0, 58, 0), random, null).entry().rarity() == Rarity.LEGENDARY) {
                legendaryAfter++;
            }
        }
        // Pull 59 is the 10th soft pity pull: LEGENDARY weight 100 + 10 × 1000 = 10 100 of 20 000.
        assertEquals(0.01, legendaryBefore / 20_000.0, 0.004);
        assertEquals(10_100 / 20_000.0, legendaryAfter / 20_000.0, 0.02);
        assertEquals(0, soft.softPityBonus(new PityState(0, 48, 0)));
        assertEquals(1_000, soft.softPityBonus(new PityState(0, 49, 0)));
    }

    @Test
    void forcedResultWinsOverGuaranteesButCountersAdvance() {
        PoolEntry common = POOL.getFirst();
        GachaRoller.Roll roll = GachaRoller.roll(POOL, DEFAULT_PITY, new PityState(9, 79, 79), new Random(1), common);
        assertSame(common, roll.entry());
        assertEquals(PityTrigger.FORCED, roll.trigger());
        assertEquals(new PityState(10, 80, 80), roll.stateAfter());
    }

    @Test
    void guaranteeForAMissingTierIsIgnored() {
        List<PoolEntry> noLegendary = POOL.stream().filter(e -> e.rarity() != Rarity.LEGENDARY).toList();
        GachaRoller.Roll roll = GachaRoller.roll(noLegendary, DEFAULT_PITY, new PityState(0, 79, 0), new Random(5), null);
        assertNotEquals(PityTrigger.LEGENDARY_PITY, roll.trigger());

        List<PoolEntry> commonsOnly = POOL.stream().filter(e -> e.rarity() == Rarity.COMMON).toList();
        GachaRoller.Roll common = GachaRoller.roll(commonsOnly, DEFAULT_PITY, new PityState(9, 79, 0), new Random(5), null);
        assertNull(common.trigger());
        assertEquals(Rarity.COMMON, common.entry().rarity());
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> sequence(RandomGenerator random, int pulls) {
        List<String> ids = new ArrayList<>();
        PityState state = PityState.ZERO;
        for (int i = 0; i < pulls; i++) {
            GachaRoller.Roll roll = GachaRoller.roll(POOL, DEFAULT_PITY, state, random, null);
            ids.add(roll.entry().entryId());
            state = roll.stateAfter();
        }
        return ids;
    }

    private static BigDecimal sum(java.util.Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static PoolEntry entry(String id, Rarity rarity, long weight) {
        CatalogItem item = new CatalogItem("ITEM_" + id, ItemKind.COSMETIC, "STICKER_PACK", null, id, rarity.name(), Map.of());
        return new PoolEntry(id, PoolRewardType.ITEM, item, 1L, null, rarity, weight, false, 0);
    }
}
