package com.dating;

import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyService;
import com.dating.economy.application.PremiumService;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Economy foundation: grants (idempotent), spends (atomic), premium days, allowances, feature gating.
 * The test profile configures TEST_PREMIUM_FEATURE and a weekly TOP_UP allowance of 5 TEST_SUPERLIKE.
 */
class EconomyIntegrationTest extends IntegrationTestBase {

    private static final String SUPERLIKE = "TEST_SUPERLIKE";
    private static final String FRAME = "TEST_FRAME";

    @Autowired
    EconomyService economy;

    @Autowired
    PremiumService premium;

    @Autowired
    FeatureAccess featureAccess;

    @BeforeEach
    void catalog() {
        jdbc.update("INSERT INTO catalog_items (code, kind) VALUES (?, 'CONSUMABLE') ON CONFLICT DO NOTHING", SUPERLIKE);
        jdbc.update("INSERT INTO catalog_items (code, kind) VALUES (?, 'COSMETIC') ON CONFLICT DO NOTHING", FRAME);
    }

    @Test
    void grantIsIdempotentAndHandlesConsumablesCosmeticsAndPremiumDays() throws Exception {
        UUID account = newAccount();
        List<Reward> rewards = List.of(new Reward.Item(SUPERLIKE, 3), new Reward.Item(FRAME, 1), new Reward.PremiumDays(7));

        assertTrue(economy.grant(account, RewardSource.ACHIEVEMENT, "first_photo", "achievement:first_photo", rewards));
        assertFalse(economy.grant(account, RewardSource.ACHIEVEMENT, "first_photo", "achievement:first_photo", rewards),
                "same idempotency key must not grant twice");

        assertEquals(3, economy.balance(account, SUPERLIKE));
        assertEquals(1, economy.balance(account, FRAME));
        Instant firstUntil = premium.premiumUntil(account).orElseThrow();
        assertApprox(Instant.now().plus(Duration.ofDays(7)), firstUntil);

        // A duplicate cosmetic stays owned once; premium days stack on top of the current period.
        economy.grant(account, RewardSource.GACHA, "pull-1", "gacha:pull-1",
                List.of(new Reward.Item(FRAME, 1), new Reward.PremiumDays(3)));
        assertEquals(1, economy.balance(account, FRAME));
        assertEquals(Duration.ofDays(3), Duration.between(firstUntil, premium.premiumUntil(account).orElseThrow()));
    }

    @Test
    void premiumDaysAfterExpiryCountFromNow() throws Exception {
        UUID account = newAccount();
        economy.grant(account, RewardSource.PROMO, null, "promo:1", List.of(new Reward.PremiumDays(1)));
        jdbc.update("UPDATE premium_entitlements SET premium_until = now() - interval '10 days' WHERE account_id = ?", account);
        assertFalse(premium.isPremium(account));

        economy.grant(account, RewardSource.PROMO, null, "promo:2", List.of(new Reward.PremiumDays(1)));
        assertTrue(premium.isPremium(account));
        assertApprox(Instant.now().plus(Duration.ofDays(1)), premium.premiumUntil(account).orElseThrow());
    }

    @Test
    void spendIsIdempotentAndNeverGoesNegativeUnderConcurrency() throws Exception {
        UUID account = newAccount();
        economy.grant(account, RewardSource.PURCHASE, "order-1", "purchase:order-1", List.of(new Reward.Item(SUPERLIKE, 5)));

        assertEquals(3, economy.spend(account, SUPERLIKE, 2, "like-1", "spend:like-1"));
        assertEquals(3, economy.spend(account, SUPERLIKE, 2, "like-1", "spend:like-1"), "retry must not spend again");

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            String key = "spend:parallel-" + i;
            pool.submit(() -> {
                start.await();
                try {
                    economy.spend(account, SUPERLIKE, 1, key, key);
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals("INSUFFICIENT_BALANCE", e.getCode());
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(3, succeeded.get());
        assertEquals(7, rejected.get());
        assertEquals(0, economy.balance(account, SUPERLIKE));
    }

    @Test
    void premiumAllowanceTopsUpOncePerPeriodAndOnlyForPremium() throws Exception {
        String freeToken = login(newPhone());
        mvc.perform(get("/api/v1/me/wallet").header("Authorization", bearer(freeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium.active").value(false))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.premiumFeatures", contains("TEST_PREMIUM_FEATURE")));

        String token = login(newPhone());
        UUID account = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        economy.grant(account, RewardSource.SUBSCRIPTION, "sub-1", "subscription:sub-1:period-1",
                List.of(new Reward.PremiumDays(30)));

        mvc.perform(get("/api/v1/me/wallet").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium.active").value(true))
                .andExpect(jsonPath("$.items[0].code").value(SUPERLIKE))
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        economy.spend(account, SUPERLIKE, 2, "like", "spend:like");

        // Same week: no second top-up.
        mvc.perform(get("/api/v1/me/wallet").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void premiumFeaturesAreGatedOthersAreOpen() throws Exception {
        UUID account = newAccount();
        assertTrue(featureAccess.isAllowed(account, "SOME_FREE_FEATURE"));
        assertFalse(featureAccess.isAllowed(account, "TEST_PREMIUM_FEATURE"));
        ApiException error = assertThrows(ApiException.class, () -> featureAccess.require(account, "TEST_PREMIUM_FEATURE"));
        assertEquals("PREMIUM_REQUIRED", error.getCode());
        assertEquals("TEST_PREMIUM_FEATURE", error.getDetails().get("feature"));

        economy.grant(account, RewardSource.ADMIN, "support", "admin:1", List.of(new Reward.PremiumDays(1)));
        assertTrue(featureAccess.isAllowed(account, "TEST_PREMIUM_FEATURE"));
    }

    @Test
    void deletingAccountWipesEconomyData() throws Exception {
        String token = login(newPhone());
        UUID account = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        economy.grant(account, RewardSource.ACHIEVEMENT, null, "achievement:x",
                List.of(new Reward.Item(SUPERLIKE, 2), new Reward.PremiumDays(5)));

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(token))).andExpect(status().isNoContent());

        for (String table : List.of("wallet_balances", "premium_entitlements", "economy_transactions")) {
            Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?", Integer.class, account);
            assertEquals(0, rows, table);
        }
    }

    private UUID newAccount() throws Exception {
        return UUID.fromString(json(get("/api/v1/me"), login(newPhone()), "$.id"));
    }

    private static void assertApprox(Instant expected, Instant actual) {
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofMinutes(1)) < 0,
                "expected ~" + expected + " but was " + actual);
    }
}
