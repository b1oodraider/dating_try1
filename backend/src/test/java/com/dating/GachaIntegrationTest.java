package com.dating;

import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyClock;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.gacha.application.GachaService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Gacha end to end: disclosed rates, pulls with pity and duplicates, idempotency, concurrency, banner windows,
 * shard exchange, history, admin and testing APIs. Uses the banners seeded by migration 010:
 * STANDARD (permanent) and LIMITED_DEMO_STARFALL (limited, one year from migration time), both with
 * EPIC+ within 10 pulls and LEGENDARY at 80.
 */
class GachaIntegrationTest extends IntegrationTestBase {

    private static final String STANDARD = "STANDARD";
    private static final String LIMITED = "LIMITED_DEMO_STARFALL";
    private static final String TICKET = "GACHA_TICKET";
    private static final String SHARD = "GACHA_SHARD";

    @Autowired
    GachaService gachaService;

    @Autowired
    EconomyClock clock;

    private record Account(String token, UUID id) {
    }

    @Test
    void bannersDiscloseRatesThatSumTo100() throws Exception {
        Account me = newAccount(false);
        MvcResult list = mvc.perform(get("/api/v1/gacha/banners").header("Authorization", bearer(me.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItems(STANDARD, LIMITED)))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].type", contains("PERMANENT")))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].cost.itemCode", contains(TICKET)))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].cost.multiPullCost", contains(10)))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].pityRules.epicOrBetterWithin", contains(10)))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].pityRules.legendaryWithin", contains(80)))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].myPity.epicGuaranteedIn", contains(10)))
                .andExpect(jsonPath("$[?(@.code == 'LIMITED_DEMO_STARFALL')].endsAt", hasSize(1)))
                .andExpect(jsonPath("$[0].pool").doesNotExist())
                .andReturn();
        for (String code : List.of(STANDARD, LIMITED)) {
            List<Number> percents = JsonPath.read(body(list), "$[?(@.code == '" + code + "')].rates.rarities[*].percent");
            assertEquals(4, percents.size());
            assertEquals(100.0, sum(percents), 1e-9, code);
            List<Object> featured = JsonPath.read(body(list), "$[?(@.code == '" + code + "')].featured[*]");
            assertFalse(featured.isEmpty(), code);
        }

        MvcResult detail = mvc.perform(get("/api/v1/gacha/banners/" + STANDARD).header("Authorization", bearer(me.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.rates.totalWeight").value(10_000))
                .andExpect(jsonPath("$.rates.rarities[?(@.rarity == 'COMMON')].percent", contains(70.0)))
                .andExpect(jsonPath("$.rates.rarities[?(@.rarity == 'LEGENDARY')].percent", contains(1.0)))
                .andExpect(jsonPath("$.pool", hasSize(15)))
                .andExpect(jsonPath("$.pool[?(@.entryId == 'premium_3d')].reward.days", contains(3)))
                .andExpect(jsonPath("$.pool[?(@.entryId == 'superlike_x3')].reward.quantity", contains(3)))
                .andExpect(jsonPath("$.pool[?(@.entryId == 'frame_neon')].reward.item.attributes.glow", contains(true)))
                .andReturn();
        assertEquals(100.0, sum(JsonPath.read(body(detail), "$.pool[*].percent")), 1e-9);

        mvc.perform(get("/api/v1/gacha/banners/NOPE").header("Authorization", bearer(me.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BANNER_NOT_FOUND"));
    }

    @Test
    void multiPullGuaranteesEpicAndDuplicatesBecomeShards() throws Exception {
        Account me = newAccount(true);
        grantTickets(me, 12);
        // Nine forced commons of the same sticker pack: one new + eight duplicates, then the 10th pull is the EPIC+ guarantee.
        force(me, STANDARD, Collections.nCopies(9, "stickers_cats"));

        pull(me, STANDARD, 10, "multi-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(10))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.results", hasSize(10)))
                .andExpect(jsonPath("$.results[0].isNew").value(true))
                .andExpect(jsonPath("$.results[0].outcome").value("NEW"))
                .andExpect(jsonPath("$.results[0].pityTrigger").value("FORCED"))
                .andExpect(jsonPath("$.results[1].outcome").value("DUPLICATE_SHARDS"))
                .andExpect(jsonPath("$.results[1].shardsGranted").value(1))
                .andExpect(jsonPath("$.results[9].rarity", anyOf(is("EPIC"), is("LEGENDARY"))))
                .andExpect(jsonPath("$.results[9].pityTrigger").value("EPIC_PITY"))
                .andExpect(jsonPath("$.shardsGranted").value(8))
                .andExpect(jsonPath("$.shardBalance.quantity").value(8))
                .andExpect(jsonPath("$.costBalance.itemCode").value(TICKET))
                .andExpect(jsonPath("$.costBalance.quantity").value(2))
                .andExpect(jsonPath("$.pity.pityGroup").value(STANDARD))
                .andExpect(jsonPath("$.pity.pullsSinceEpic").value(0))
                .andExpect(jsonPath("$.pity.totalPulls").value(10));
        assertEquals(1, economyService.balance(me.id(), "STICKERS_STUB_CATS"));

        // A cosmetic owned from an earlier request is a duplicate too: RARE → 5 shards.
        force(me, STANDARD, List.of("status_early_bird", "status_early_bird"));
        pull(me, STANDARD, 1, "single-1").andExpect(jsonPath("$.results[0].outcome").value("NEW"));
        pull(me, STANDARD, 1, "single-2")
                .andExpect(jsonPath("$.results[0].outcome").value("DUPLICATE_SHARDS"))
                .andExpect(jsonPath("$.results[0].isNew").value(false))
                .andExpect(jsonPath("$.results[0].shardsGranted").value(5))
                .andExpect(jsonPath("$.shardBalance.quantity").value(13))
                .andExpect(jsonPath("$.costBalance.quantity").value(0));
        assertEquals(1, economyService.balance(me.id(), "STATUS_STUB_EARLY_BIRD"));
    }

    @Test
    void everyMultiPullContainsEpicOrBetter() throws Exception {
        Account me = newAccount(false);
        grantTickets(me, 30);
        for (int i = 0; i < 3; i++) {
            MvcResult result = pull(me, STANDARD, 10, "ten-" + i).andExpect(status().isOk()).andReturn();
            List<String> rarities = JsonPath.read(body(result), "$.results[*].rarity");
            assertTrue(rarities.contains("EPIC") || rarities.contains("LEGENDARY"), rarities.toString());
        }
        mvc.perform(get("/api/v1/gacha/banners").header("Authorization", bearer(me.token())))
                .andExpect(jsonPath("$[?(@.code == 'STANDARD')].myPity.totalPulls", contains(30)))
                // limited banners count in their own pity group
                .andExpect(jsonPath("$[?(@.code == 'LIMITED_DEMO_STARFALL')].myPity.totalPulls", contains(0)));
        assertEquals(0, economyService.balance(me.id(), TICKET));
    }

    @Test
    void legendaryGuaranteeAtEightyAndCountersReset() throws Exception {
        Account me = newAccount(true);
        grantTickets(me, 1);
        testing(me, put("/api/v1/testing/gacha/pity/LIMITED"), "{\"pullsSinceEpic\": 5, \"pullsSinceLegendary\": 79}")
                .andExpect(jsonPath("$.pity.LIMITED.pullsSinceLegendary").value(79));

        pull(me, LIMITED, 1, "legendary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].rarity").value("LEGENDARY"))
                .andExpect(jsonPath("$.results[0].pityTrigger").value("LEGENDARY_PITY"))
                .andExpect(jsonPath("$.pity.pityGroup").value("LIMITED"))
                .andExpect(jsonPath("$.pity.pullsSinceLegendary").value(0))
                .andExpect(jsonPath("$.pity.pullsSinceEpic").value(0))
                .andExpect(jsonPath("$.pity.legendaryGuaranteedIn").value(80));

        testing(me, delete("/api/v1/testing/gacha/pity"), null).andExpect(jsonPath("$.pity", anEmptyMap()));
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameResultsWithoutSpending() throws Exception {
        Account me = newAccount(false);
        grantTickets(me, 10);
        MvcResult first = pull(me, STANDARD, 10, "retry-key").andExpect(status().isOk()).andReturn();
        MvcResult second = pull(me, STANDARD, 10, "retry-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.costBalance.quantity").value(0))
                .andReturn();
        assertEquals((String) JsonPath.read(body(first), "$.requestId"), JsonPath.read(body(second), "$.requestId"));
        assertEquals((List<?>) JsonPath.read(body(first), "$.results[*].entryId"),
                JsonPath.read(body(second), "$.results[*].entryId"));
        assertEquals((List<?>) JsonPath.read(body(first), "$.results[*].outcome"),
                JsonPath.read(body(second), "$.results[*].outcome"));

        pull(me, STANDARD, 1, "retry-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(10, countRows("gacha_pulls", me.id()));
        assertEquals(1, countRows("gacha_pull_requests", me.id()));
    }

    @Test
    void insufficientTicketsChangeNothing() throws Exception {
        Account me = newAccount(false);
        grantTickets(me, 5);
        pull(me, STANDARD, 10, "too-expensive")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.details.itemCode").value(TICKET))
                .andExpect(jsonPath("$.details.available").value(5));
        assertEquals(5, economyService.balance(me.id(), TICKET));
        assertEquals(0, countRows("gacha_pulls", me.id()));
        assertEquals(0, countRows("gacha_pull_requests", me.id()));
        assertEquals(0, countRows("gacha_pity_counters", me.id()));

        pull(me, STANDARD, 3, "odd-count")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PULL_COUNT"));
        pull(me, STANDARD, 10, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // The same key works after a top-up (the failed attempt left nothing behind).
        grantTickets(me, 5);
        pull(me, STANDARD, 10, "too-expensive").andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void bannerOutsideItsWindowIsNotActive() throws Exception {
        Account me = newAccount(false);
        grantTickets(me, 2);
        pull(me, LIMITED, 1, "in-window").andExpect(status().isOk());

        clock.advance(me.id(), Duration.ofDays(400)); // this account's economy time is after the demo banner ended
        pull(me, LIMITED, 1, "after-end")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANNER_NOT_ACTIVE"));
        mvc.perform(get("/api/v1/gacha/banners").header("Authorization", bearer(me.token())))
                .andExpect(jsonPath("$[*].code", hasItem(STANDARD)))
                .andExpect(jsonPath("$[*].code", not(hasItem(LIMITED))));
        // An ended banner keeps its disclosed rates.
        mvc.perform(get("/api/v1/gacha/banners/" + LIMITED).header("Authorization", bearer(me.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
        assertEquals(1, economyService.balance(me.id(), TICKET));
        pull(me, STANDARD, 1, "permanent-still-open").andExpect(status().isOk());

        // A banner that has not started yet: not listed, not found, not pullable.
        Account editor = newAccount(true);
        String future = "FUTURE_" + suffix();
        admin(editor, post("/api/v1/admin/gacha/banners"), bannerJson(future, "LIMITED",
                Instant.now().plus(10, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS)), 201);
        admin(editor, put("/api/v1/admin/gacha/banners/" + future + "/pool/cats"),
                "{\"rewardType\": \"ITEM\", \"itemCode\": \"STICKERS_STUB_CATS\", \"weight\": 1}", 200);
        Account other = newAccount(false);
        grantTickets(other, 1);
        pull(other, future, 1, "too-early").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANNER_NOT_ACTIVE"));
        mvc.perform(get("/api/v1/gacha/banners/" + future).header("Authorization", bearer(other.token())))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentPullsNeverOverspend() throws Exception {
        Account me = newAccount(false);
        grantTickets(me, 3);

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            String key = "parallel-" + i;
            pool.submit(() -> {
                start.await();
                try {
                    gachaService.pull(me.id(), STANDARD, 1, key);
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
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(3, succeeded.get());
        assertEquals(7, rejected.get());
        assertEquals(0, economyService.balance(me.id(), TICKET));
        assertEquals(3, countRows("gacha_pulls", me.id()));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT total_pulls FROM gacha_pity_counters WHERE account_id = ? AND pity_group = 'STANDARD'",
                Long.class, me.id()));
    }

    @Test
    void historyIsPagedNewestFirst() throws Exception {
        Account me = newAccount(false);
        grantTickets(me, 12);
        pull(me, STANDARD, 10, "h-1").andExpect(status().isOk());
        pull(me, STANDARD, 1, "h-2").andExpect(status().isOk());
        pull(me, LIMITED, 1, "h-3").andExpect(status().isOk());

        MvcResult page1 = history(me, "?limit=5")
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].bannerCode").value(LIMITED))
                .andExpect(jsonPath("$.items[0].bannerTitle").value("Звёздный фестиваль (демо)"))
                .andExpect(jsonPath("$.items[1].bannerCode").value(STANDARD))
                .andExpect(jsonPath("$.items[2].index").value(9))
                .andExpect(jsonPath("$.nextBefore").isNumber())
                .andReturn();
        Number before = JsonPath.read(body(page1), "$.nextBefore");
        MvcResult page2 = history(me, "?limit=5&before=" + before.longValue())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andReturn();
        Number before2 = JsonPath.read(body(page2), "$.nextBefore");
        history(me, "?limit=5&before=" + before2.longValue())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].index").value(0))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
        mvc.perform(get("/api/v1/gacha/history?limit=0").header("Authorization", bearer(me.token())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shardsAreExchangedForTicketsIdempotently() throws Exception {
        Account me = newAccount(false);
        economyService.grant(me.id(), RewardSource.TESTING, "shards", "test-shards:" + UUID.randomUUID(),
                List.of(new Reward.Item(SHARD, 65)));
        mvc.perform(get("/api/v1/gacha/shards").header("Authorization", bearer(me.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.quantity").value(65))
                .andExpect(jsonPath("$.shardsPerTicket").value(30))
                .andExpect(jsonPath("$.ticketItemCode").value(TICKET))
                .andExpect(jsonPath("$.duplicateShards.LEGENDARY").value(60));

        exchange(me, 2, "exchange-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.shardsSpent").value(60))
                .andExpect(jsonPath("$.shardBalance.quantity").value(5))
                .andExpect(jsonPath("$.ticketBalance.quantity").value(2));
        exchange(me, 2, "exchange-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.shardBalance.quantity").value(5))
                .andExpect(jsonPath("$.ticketBalance.quantity").value(2));
        exchange(me, 1, "exchange-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.details.itemCode").value(SHARD));
        exchange(me, 0, "exchange-3").andExpect(status().isBadRequest());
        assertEquals(5, economyService.balance(me.id(), SHARD));
        assertEquals(2, economyService.balance(me.id(), TICKET));
    }

    @Test
    void deletingAccountWipesGachaData() throws Exception {
        Account me = newAccount(true);
        grantTickets(me, 1);
        pull(me, STANDARD, 1, "before-delete").andExpect(status().isOk());
        force(me, STANDARD, List.of("frame_gold"));

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(me.token()))).andExpect(status().isNoContent());

        for (String table : List.of("gacha_pulls", "gacha_pull_requests", "gacha_pity_counters", "gacha_test_forced_pulls")) {
            assertEquals(0, countRows(table, me.id()), table);
        }
    }

    @Test
    void adminManagesBannersAndPools() throws Exception {
        Account outsider = newAccount(false);
        admin(outsider, get("/api/v1/admin/gacha/banners"), null, 403).andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));

        Account editor = newAccount(true);
        String code = "COLLAB_" + suffix();
        admin(editor, post("/api/v1/admin/gacha/banners"), bannerJson(code, "LIMITED", null, null), 400)
                .andExpect(jsonPath("$.fieldErrors.endsAt").exists());
        admin(editor, post("/api/v1/admin/gacha/banners"), bannerJson("bad code", "PERMANENT", null, null), 400)
                .andExpect(jsonPath("$.fieldErrors.code").exists());
        admin(editor, post("/api/v1/admin/gacha/banners"),
                bannerJson(code, "LIMITED", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(7, ChronoUnit.DAYS)), 201)
                .andExpect(jsonPath("$.pityGroup").value(code))
                .andExpect(jsonPath("$.pityRules.epicOrBetterWithin").value(10))
                .andExpect(jsonPath("$.pool", hasSize(0)));
        admin(editor, post("/api/v1/admin/gacha/banners"), bannerJson(code, "PERMANENT", null, null), 409)
                .andExpect(jsonPath("$.code").value("BANNER_ALREADY_EXISTS"));

        // An empty pool is not offered to users.
        mvc.perform(get("/api/v1/gacha/banners").header("Authorization", bearer(outsider.token())))
                .andExpect(jsonPath("$[*].code", not(hasItem(code))));

        String pool = "/api/v1/admin/gacha/banners/" + code + "/pool/";
        admin(editor, put(pool + "frame"), "{\"rewardType\": \"ITEM\", \"itemCode\": \"FRAME_STUB_STARLIGHT\", \"weight\": 1, \"featured\": true}", 200)
                .andExpect(jsonPath("$.pool[0].rarity").value("LEGENDARY"));
        admin(editor, put(pool + "likes"), "{\"rewardType\": \"ITEM\", \"itemCode\": \"SUPERLIKE\", \"quantity\": 2, \"rarity\": \"COMMON\", \"weight\": 2}", 200);
        admin(editor, put(pool + "premium"), "{\"rewardType\": \"PREMIUM_DAYS\", \"days\": 1, \"rarity\": \"RARE\", \"weight\": 1}", 200)
                .andExpect(jsonPath("$.rates.totalWeight").value(4))
                .andExpect(jsonPath("$.pool[?(@.entryId == 'likes')].percent", contains(50.0)));
        admin(editor, put(pool + "wrong"), "{\"rewardType\": \"ITEM\", \"itemCode\": \"FRAME_STUB_GOLD\", \"rarity\": \"COMMON\", \"weight\": 1}", 400)
                .andExpect(jsonPath("$.fieldErrors.rarity").exists());
        admin(editor, put(pool + "two-frames"), "{\"rewardType\": \"ITEM\", \"itemCode\": \"FRAME_STUB_GOLD\", \"quantity\": 2, \"weight\": 1}", 400)
                .andExpect(jsonPath("$.fieldErrors.quantity").exists());
        admin(editor, put(pool + "ghost"), "{\"rewardType\": \"ITEM\", \"itemCode\": \"NO_SUCH_ITEM\", \"weight\": 1}", 404)
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
        admin(editor, delete(pool + "premium"), null, 200).andExpect(jsonPath("$.pool", hasSize(2)));
        admin(editor, delete(pool + "premium"), null, 404).andExpect(jsonPath("$.code").value("POOL_ENTRY_NOT_FOUND"));

        mvc.perform(get("/api/v1/gacha/banners/" + code).header("Authorization", bearer(outsider.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured[0].entryId").value("frame"))
                .andExpect(jsonPath("$.rates.rarities[?(@.rarity == 'COMMON')].percent", contains(66.6667)))
                .andExpect(jsonPath("$.rates.rarities[?(@.rarity == 'LEGENDARY')].percent", contains(33.3333)));

        // Deactivated → not pullable; a pulled banner cannot be deleted, an unused one can.
        String updated = bannerJson(code, "PERMANENT", null, null).replace("\"active\": true", "\"active\": false");
        admin(editor, put("/api/v1/admin/gacha/banners/" + code), updated, 200).andExpect(jsonPath("$.active").value(false));
        grantTickets(outsider, 1);
        pull(outsider, code, 1, "deactivated").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANNER_NOT_ACTIVE"));
        admin(editor, put("/api/v1/admin/gacha/banners/" + code), bannerJson(code, "PERMANENT", null, null), 200);
        pull(outsider, code, 1, "activated").andExpect(status().isOk());
        admin(editor, delete("/api/v1/admin/gacha/banners/" + code), null, 409)
                .andExpect(jsonPath("$.code").value("BANNER_HAS_PULLS"));
        admin(editor, get("/api/v1/admin/gacha/banners/" + code), null, 200).andExpect(jsonPath("$.pulls").value(1));

        String unused = "UNUSED_" + suffix();
        admin(editor, post("/api/v1/admin/gacha/banners"), bannerJson(unused, "PERMANENT", null, null), 201);
        admin(editor, delete("/api/v1/admin/gacha/banners/" + unused), null, 204);
        admin(editor, get("/api/v1/admin/gacha/banners/" + unused), null, 404);
    }

    @Test
    void testingApiIsForTestAccountsAndValidatesEntries() throws Exception {
        Account real = newAccount(false);
        testing(real, get("/api/v1/testing/gacha/state"), null, 403)
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_REQUIRED"));

        Account tester = newAccount(true);
        testing(tester, put("/api/v1/testing/gacha/banners/" + STANDARD + "/forced"), "{\"entryIds\": [\"nope\"]}", 404)
                .andExpect(jsonPath("$.code").value("POOL_ENTRY_NOT_FOUND"));
        force(tester, STANDARD, List.of("frame_neon", "boost_x1"))
                .andExpect(jsonPath("$.forcedQueue.STANDARD", contains("frame_neon", "boost_x1")));
        grantTickets(tester, 1);
        pull(tester, STANDARD, 1, "forced-1")
                .andExpect(jsonPath("$.results[0].entryId").value("frame_neon"))
                .andExpect(jsonPath("$.results[0].reward.item.code").value("FRAME_STUB_NEON"));
        testing(tester, get("/api/v1/testing/gacha/state"), null, 200)
                .andExpect(jsonPath("$.forcedQueue.STANDARD", contains("boost_x1")))
                .andExpect(jsonPath("$.pulls").value(1));
        testing(tester, delete("/api/v1/testing/gacha/forced"), null, 200)
                .andExpect(jsonPath("$.forcedQueue", anEmptyMap()));
        testing(tester, delete("/api/v1/testing/gacha/history"), null, 200)
                .andExpect(jsonPath("$.pulls").value(0));
        history(tester, "").andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A fresh account; test accounts are flagged directly (the allowlisted test phones are shared with other tests).
     */
    private Account newAccount(boolean testAccount) throws Exception {
        String token = login(newPhone());
        UUID id = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        if (testAccount) {
            jdbc.update("UPDATE accounts SET test_account = TRUE WHERE id = ?", id);
        }
        return new Account(token, id);
    }

    private void grantTickets(Account account, long quantity) {
        economyService.grant(account.id(), RewardSource.TESTING, "tickets", "test-tickets:" + UUID.randomUUID(),
                List.of(new Reward.Item(TICKET, quantity)));
    }

    private ResultActions pull(Account account, String banner, int count, String key) throws Exception {
        return mvc.perform(post("/api/v1/gacha/banners/" + banner + "/pull")
                .header("Authorization", bearer(account.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\": " + count + ", \"idempotencyKey\": \"" + key + "\"}"));
    }

    private ResultActions exchange(Account account, int tickets, String key) throws Exception {
        return mvc.perform(post("/api/v1/gacha/shards/exchange")
                .header("Authorization", bearer(account.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tickets\": " + tickets + ", \"idempotencyKey\": \"" + key + "\"}"));
    }

    private ResultActions history(Account account, String query) throws Exception {
        return mvc.perform(get("/api/v1/gacha/history" + query).header("Authorization", bearer(account.token())))
                .andExpect(status().isOk());
    }

    private ResultActions force(Account account, String banner, List<String> entryIds) throws Exception {
        String ids = entryIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return testing(account, put("/api/v1/testing/gacha/banners/" + banner + "/forced"), "{\"entryIds\": [" + ids + "]}", 200);
    }

    private ResultActions testing(Account account, MockHttpServletRequestBuilder request, String json) throws Exception {
        return testing(account, request, json, 200);
    }

    private ResultActions testing(Account account, MockHttpServletRequestBuilder request, String json,
                                  int expectedStatus) throws Exception {
        return admin(account, request, json, expectedStatus);
    }

    private ResultActions admin(Account account, MockHttpServletRequestBuilder request, String json,
                                int expectedStatus) throws Exception {
        request.header("Authorization", bearer(account.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(json);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    private static String bannerJson(String code, String type, Instant startsAt, Instant endsAt) {
        return "{\"code\": \"" + code + "\", \"title\": \"Коллаборация\", \"type\": \"" + type + "\", \"active\": true"
                + (startsAt == null ? "" : ", \"startsAt\": \"" + startsAt + "\"")
                + (endsAt == null ? "" : ", \"endsAt\": \"" + endsAt + "\"") + "}";
    }

    private int countRows(String table, UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?", Integer.class, accountId);
    }

    private static double sum(List<Number> values) {
        return values.stream().mapToDouble(Number::doubleValue).sum();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace('-', '_');
    }
}
