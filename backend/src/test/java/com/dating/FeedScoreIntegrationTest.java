package com.dating;

import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The feed is ordered by score (docs/matching.md, part 2); a boost adds points, it does not jump the queue. */
class FeedScoreIntegrationTest extends AdminTestSupport {

    @Test
    void bestMatchStaysAboveABoostAndTheBoostLiftsAnEqualOne() throws Exception {
        String city = uniqueCity();
        String viewer = login(newPhone());
        publishWith(viewer, "Зритель", city, List.of());
        putProfile(viewer, "{\"goalCodes\":[\"SERIOUS_RELATIONSHIP\",\"FAMILY\"]}").andExpect(status().isOk());

        // Ideal: shared goals, three photos, a real «about».
        String ideal = login(newPhone());
        publishWith(ideal, "Идеальная", city, List.of());
        uploadPng(ideal);
        uploadPng(ideal);
        putProfile(ideal, "{\"goalCodes\":[\"SERIOUS_RELATIONSHIP\",\"FAMILY\"],\"aboutText\":"
                + "\"Люблю горы, хорошие книги и долгие разговоры на кухне до утра. Ищу своего человека.\"}")
                .andExpect(status().isOk());
        String boosted = login(newPhone());
        publishWith(boosted, "С-бустом", city, List.of());
        String plain = login(newPhone());
        publishWith(plain, "Обычная", city, List.of());

        grantBoost(boosted);
        mvc.perform(post("/api/v1/me/boost").header("Authorization", bearer(boosted))
                        .contentType("application/json").content("{\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isOk());

        discovery(viewer, city, null, null, 20)
                .andExpect(jsonPath("$.items[*].displayName", contains("Идеальная", "С-бустом", "Обычная")));

        // The breakdown explains the order to a moderator.
        String moderator = adminToken();
        as(moderator, get("/api/v1/admin/discovery/score")
                .queryParam("viewerAccountId", accountId(viewer))
                .queryParam("candidateProfileId", json(get("/api/v1/me/profile"), boosted, "$.id")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boost").value(12.0))
                .andExpect(jsonPath("$.total").value(greaterThan(0.0)));
        as(viewer, get("/api/v1/admin/discovery/score")
                .queryParam("viewerAccountId", accountId(viewer))
                .queryParam("candidateProfileId", UUID.randomUUID().toString()), null)
                .andExpect(status().isForbidden());
    }

    private void grantBoost(String token) throws Exception {
        UUID account = UUID.fromString(accountId(token));
        economyService.grant(account, RewardSource.ADMIN, "test", "test-boost:" + UUID.randomUUID(),
                List.of(new Reward.Item("BOOST", 1)));
    }
}
