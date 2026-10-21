package com.dating;

import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Profile boost: activation (spend, idempotency, no stacking), ranking in discovery (only among matching
 * profiles, stable pagination, expiry), impressions, account deletion and the testing API.
 * Expiry is simulated by moving the stored boost period into the past.
 */
class BoostIntegrationTest extends IntegrationTestBase {

    private static final String TEST_OTP = "654321";

    @Test
    void boostedProfileGoesFirstOnlyAmongMatchingProfilesAndExpiryRestoresOrder() throws Exception {
        String city = uniqueCity();
        String otherCity = uniqueCity();
        String smoker = login(newPhone());
        publishWith(smoker, "Анна", city, List.of());
        putProfile(smoker, "{\"aboutText\":\"Иногда курю на балконе\"}").andExpect(status().isOk());
        publishWith(login(newPhone()), "Бэлла", city, List.of("PACE_ASYNCHRONOUS"));
        publishWith(login(newPhone()), "Вера", city, List.of("PACE_ASYNCHRONOUS"));
        String elsewhere = login(newPhone());
        publishWith(elsewhere, "Дина", otherCity, List.of("PACE_ASYNCHRONOUS"));

        String viewer = login(newPhone());
        List<String> normalOrder = names(discovery(viewer, city, null, null, 20));
        assertEquals(3, normalOrder.size());
        assertNotEquals("Анна", normalOrder.get(0), "the boost must change the order for this test to mean anything");

        activate(smoker, 1, "k-smoker");
        activate(elsewhere, 1, "k-elsewhere");

        List<String> boostedOrder = new ArrayList<>(normalOrder);
        boostedOrder.remove("Анна");
        boostedOrder.addFirst("Анна");
        discovery(viewer, city, null, null, 20)
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].displayName", contains(boostedOrder.toArray())))
                .andExpect(jsonPath("$.items[0].boosted").doesNotExist()); // app.boost.public-badge=false

        // Filters still apply: a boost from another city or without the requested tag never shows up.
        discovery(viewer, city, "PACE_ASYNCHRONOUS", "OR", 20)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Анна"))))
                .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Дина"))));
        discovery(viewer, otherCity, null, null, 20)
                .andExpect(jsonPath("$.items[*].displayName", contains("Дина")));

        // Neither do exclusion words.
        grantPremium(viewer, 30);
        putWords(viewer, "[\"курить\"]").andExpect(status().isOk());
        discovery(viewer, city, null, null, 20)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Анна"))));
        putWords(viewer, "[]").andExpect(status().isOk());

        expireBoosts(smoker);
        discovery(viewer, city, null, null, 20)
                .andExpect(jsonPath("$.items[*].displayName", contains(normalOrder.toArray())));
        boostStatus(smoker)
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.startsAt").value(nullValue()))
                .andExpect(jsonPath("$.history", hasSize(1)));
    }

    @Test
    void paginationOverBoostedProfilesIsStableAndTotalsAreExact() throws Exception {
        String city = uniqueCity();
        List<String> boosted = List.of("Бусь-1", "Бусь-2", "Бусь-3");
        for (String name : boosted) {
            String token = login(newPhone());
            publishWith(token, name, city, List.of());
            activate(token, 1, "k");
        }
        for (String name : List.of("Обыч-1", "Обыч-2", "Обыч-3", "Обыч-4")) {
            publishWith(login(newPhone()), name, city, List.of());
        }

        String viewer = login(newPhone());
        List<String> firstPass = allPages(viewer, city, 2);
        assertEquals(7, firstPass.size());
        assertEquals(7, new HashSet<>(firstPass).size(), "no profile may repeat or be skipped across pages");
        assertEquals(new HashSet<>(boosted), new HashSet<>(firstPass.subList(0, 3)), "boosted profiles come first");

        // Impressions changed while paging, the order for this viewer did not.
        assertEquals(firstPass, allPages(viewer, city, 2));
        assertEquals(firstPass, allPages(viewer, city, 3));
    }

    @Test
    void activationSpendsOnceIsIdempotentAndDoesNotStack() throws Exception {
        String owner = login(newPhone());
        publishWith(owner, "Владелец", uniqueCity(), List.of());
        grantBoosts(owner, 2);

        boostStatus(owner)
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.durationMinutes").value(30))
                .andExpect(jsonPath("$.history", hasSize(0)));

        MvcResult started = boost(owner, "{\"idempotencyKey\":\"tap-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.impressions").value(0))
                .andExpect(jsonPath("$.available").value(1))
                .andReturn();
        Instant startsAt = Instant.parse(JsonPath.read(body(started), "$.startsAt"));
        Instant endsAt = Instant.parse(JsonPath.read(body(started), "$.endsAt"));
        assertEquals(Duration.ofMinutes(30), Duration.between(startsAt, endsAt));

        // A retry (same key) returns the same boost and does not spend again.
        boost(owner, "{\"idempotencyKey\":\"tap-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.endsAt").value(JsonPath.<String>read(body(started), "$.endsAt")))
                .andExpect(jsonPath("$.history", hasSize(1)));
        // A new tap while active: rejected without spending.
        boost(owner, "{\"idempotencyKey\":\"tap-2\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOST_ALREADY_ACTIVE"));
        boostStatus(owner).andExpect(jsonPath("$.available").value(1));
        assertEquals(1, spends(owner));

        // After the boost ends, the old key still does nothing; a new key starts a new boost.
        expireBoosts(owner);
        boost(owner, "{\"idempotencyKey\":\"tap-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.available").value(1));
        boost(owner, "{\"idempotencyKey\":\"tap-3\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.history", hasSize(2)));
        assertEquals(2, spends(owner));

        boost(owner, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").exists());
    }

    @Test
    void activationNeedsPublishedProfileAndBalance() throws Exception {
        String draft = login(newPhone());
        grantBoosts(draft, 1);
        boost(draft, "{\"idempotencyKey\":\"a\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_PUBLISHED"));
        boostStatus(draft).andExpect(jsonPath("$.available").value(1));

        String poor = login(newPhone());
        publishWith(poor, "Без бустов", uniqueCity(), List.of());
        boost(poor, "{\"idempotencyKey\":\"b\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.details.itemCode").value("BOOST"))
                .andExpect(jsonPath("$.details.available").value(0));
        boostStatus(poor).andExpect(jsonPath("$.history", hasSize(0)));

        // The failed attempt left nothing behind: the same key works after a top-up.
        grantBoosts(poor, 1);
        boost(poor, "{\"idempotencyKey\":\"b\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void impressionsAreCountedOncePerPageServedToOtherViewers() throws Exception {
        String city = uniqueCity();
        String owner = login(newPhone());
        publishWith(owner, "Звезда", city, List.of());
        String plain = login(newPhone());
        publishWith(plain, "Сосед", city, List.of());
        activate(owner, 1, "k");

        String viewer = login(newPhone());
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.items[0].displayName").value("Звезда"));
        discovery(viewer, city, null, null, 20);
        discovery(login(newPhone()), city, null, null, 20);
        boostStatus(owner).andExpect(jsonPath("$.impressions").value(3));

        // Not counted: the owner's own feed, pages without the profile, other cities.
        discovery(owner, city, null, null, 20).andExpect(jsonPath("$.items[*].displayName", contains("Сосед")));
        page(viewer, city, 1, 1).andExpect(jsonPath("$.items[*].displayName", contains("Сосед")));
        discovery(viewer, uniqueCity(), null, null, 20);
        boostStatus(owner)
                .andExpect(jsonPath("$.impressions").value(3))
                .andExpect(jsonPath("$.history[0].impressions").value(3));
        assertEquals(0, rows(plain), "profiles without a boost get no counters");

        // After the boost ends, showing the profile no longer counts.
        expireBoosts(owner);
        discovery(viewer, city, null, null, 20);
        boostStatus(owner)
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.impressions").value(0))
                .andExpect(jsonPath("$.history[0].impressions").value(3));
    }

    @Test
    void deletingAccountWipesBoosts() throws Exception {
        String owner = login(newPhone());
        publishWith(owner, "Удаляемый", uniqueCity(), List.of());
        activate(owner, 1, "k");
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), owner, "$.id"));
        assertEquals(1, rows(owner));

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(owner))).andExpect(status().isNoContent());

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM profile_boosts WHERE account_id = ?", Integer.class, accountId));
    }

    @Test
    void testingApiStartsEndsAndEditsBoostsWithoutSpending() throws Exception {
        testing(login(newPhone()), get("/api/v1/testing/boost"), null, 403)
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_REQUIRED"));

        String city = uniqueCity();
        String tester = testLogin("+78000000007");
        String first = seedProfile(tester, "Первая", city);
        String second = seedProfile(tester, "Вторая", city);

        String firstBoost = "/api/v1/testing/boost?accountId=" + first;
        testing(tester, post(firstBoost), "{\"minutes\": 10}")
                .andExpect(jsonPath("$.accountId").value(first))
                .andExpect(jsonPath("$.status.active").value(true))
                .andExpect(jsonPath("$.status.available").value(0))
                .andExpect(jsonPath("$.boosts", hasSize(1)));
        testing(tester, post(firstBoost), null, 409).andExpect(jsonPath("$.code").value("BOOST_ALREADY_ACTIVE"));

        discovery(tester, city, null, null, 20).andExpect(jsonPath("$.items[*].displayName", contains("Первая", "Вторая")));
        discovery(login(newPhone()), city, null, null, 20).andExpect(jsonPath("$.totalElements").value(0)); // isolation

        testing(tester, put("/api/v1/testing/boost/impressions?accountId=" + first), "{\"impressions\": 42}")
                .andExpect(jsonPath("$.status.impressions").value(42));
        testing(tester, delete(firstBoost), null)
                .andExpect(jsonPath("$.status.active").value(false))
                .andExpect(jsonPath("$.boosts[0].impressions").value(42));
        testing(tester, post(firstBoost), null)
                .andExpect(jsonPath("$.status.active").value(true))
                .andExpect(jsonPath("$.boosts", hasSize(2)));

        testing(tester, get("/api/v1/testing/boost?accountId=" + UUID.randomUUID()), null, 404)
                .andExpect(jsonPath("$.code").value("SEED_PROFILE_NOT_FOUND"));
        testing(tester, post("/api/v1/testing/boost"), null, 400)
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_PUBLISHED"));
        testing(tester, put("/api/v1/testing/boost/impressions"), "{\"impressions\": 1}", 404)
                .andExpect(jsonPath("$.code").value("BOOST_NOT_FOUND"));
        assertNotNull(second);
    }

    @Test
    void boostLivesOnTheOwnersClock() throws Exception {
        String city = uniqueCity();
        String tester = testLogin("+78000000008");
        publishWith(tester, "Тестер", city, List.of());
        String viewer = testLogin("+78000000009");
        seedProfile(viewer, "Соседка", city); // newer than the tester's profile
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.items[0].displayName").value("Соседка"));

        testing(tester, post("/api/v1/testing/boost"), null).andExpect(jsonPath("$.status.active").value(true));
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.items[0].displayName").value("Тестер"));

        // A day later on the owner's clock the boost is over for the owner and in everyone's feed.
        testing(tester, post("/api/v1/testing/clock/advance"), "{\"days\": 1}");
        boostStatus(tester).andExpect(jsonPath("$.active").value(false));
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.items[0].displayName").value("Соседка"));

        // Back to real time: still within the 30 minutes, the boost is active again.
        testing(tester, delete("/api/v1/testing/clock"), null);
        boostStatus(tester).andExpect(jsonPath("$.active").value(true));
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.items[0].displayName").value("Тестер"));
        testing(tester, delete("/api/v1/testing/boost"), null).andExpect(jsonPath("$.status.active").value(false));
    }

    // ------------------------------------------------------------------ helpers

    private void activate(String token, int boosts, String key) throws Exception {
        grantBoosts(token, boosts);
        boost(token, "{\"idempotencyKey\":\"" + key + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    private void grantBoosts(String token, int quantity) throws Exception {
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        economyService.grant(accountId, RewardSource.ADMIN, "test", "test-boost:" + UUID.randomUUID(),
                List.of(new Reward.Item("BOOST", quantity)));
    }

    private ResultActions boost(String token, String json) throws Exception {
        return mvc.perform(post("/api/v1/me/boost")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions boostStatus(String token) throws Exception {
        return mvc.perform(get("/api/v1/me/boost").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    /** Moves the account's boosts an hour into the past, as if they had run out naturally. */
    private void expireBoosts(String token) throws Exception {
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        jdbc.update("UPDATE profile_boosts SET starts_at = starts_at - interval '1 hour', ends_at = ends_at - interval '1 hour'"
                + " WHERE account_id = ?", accountId);
    }

    private int spends(String token) throws Exception {
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        return jdbc.queryForObject("SELECT count(*) FROM economy_transactions WHERE account_id = ? AND type = 'SPEND'",
                Integer.class, accountId);
    }

    private int rows(String token) throws Exception {
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        return jdbc.queryForObject("SELECT count(*) FROM profile_boosts WHERE account_id = ?", Integer.class, accountId);
    }

    private ResultActions page(String token, String city, int page, int limit) throws Exception {
        canSearch(token);
        return mvc.perform(get("/api/v1/discovery/profiles")
                        .header("Authorization", bearer(token))
                        .queryParam("city", city)
                        .queryParam("page", String.valueOf(page))
                        .queryParam("limit", String.valueOf(limit)))
                .andExpect(status().isOk());
    }

    private List<String> allPages(String token, String city, int limit) throws Exception {
        List<String> names = new ArrayList<>();
        int totalPages = Integer.MAX_VALUE;
        for (int p = 0; p < totalPages; p++) {
            MvcResult result = page(token, city, p, limit).andReturn();
            totalPages = JsonPath.read(body(result), "$.totalPages");
            assertEquals(7, (int) JsonPath.read(body(result), "$.totalElements"));
            names.addAll(JsonPath.read(body(result), "$.items[*].displayName"));
        }
        return names;
    }

    private static List<String> names(ResultActions discovery) throws Exception {
        return JsonPath.read(body(discovery.andReturn()), "$.items[*].displayName");
    }

    /** Creates and publishes a seed profile (visible to test accounts only); returns its account id. */
    private String seedProfile(String tester, String name, String city) throws Exception {
        MvcResult created = testing(tester, post("/api/v1/testing/profiles"),
                "{\"profile\": {\"gender\": \"MALE\", \"seeking\": \"MALE\", \"displayName\": \"" + name + "\", \"age\": 30, \"city\": \"" + city + "\"}}", 201)
                .andReturn();
        String seedId = JsonPath.read(body(created), "$.accountId");
        mvc.perform(multipart("/api/v1/testing/profiles/" + seedId + "/photos")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(3, 3)))
                        .header("Authorization", bearer(tester)))
                .andExpect(status().isOk());
        testing(tester, post("/api/v1/testing/profiles/" + seedId + "/publish"), null);
        return seedId;
    }

    private String testLogin(String phone) throws Exception {
        MvcResult challenge = mvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phoneBody(phone)))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = JsonPath.read(body(challenge), "$.challengeId");
        MvcResult session = verify(challengeId, TEST_OTP).andExpect(status().isOk()).andReturn();
        return JsonPath.read(body(session), "$.token");
    }

    private ResultActions testing(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
        return testing(token, request, json, 200);
    }

    private ResultActions testing(String token, MockHttpServletRequestBuilder request, String json,
                                  int expectedStatus) throws Exception {
        request.header("Authorization", bearer(token));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus));
    }
}
