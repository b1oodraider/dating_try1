package com.dating;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The owner sees completeness as concrete tips, the most valuable missing one first. */
class ProfileTipsIntegrationTest extends IntegrationTestBase {

    @Test
    void tipsExplainTheCompletenessPercent() throws Exception {
        String token = login(newPhone());
        publishWith(token, "Анна", uniqueCity(), List.of("PACE_FAST_CHAT", "FORMAT_WALK_FIRST"));

        // name 10 + age 10 + city 10 + first photo 15 + cards in 2 categories 10
        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completenessPercent").value(55))
                .andExpect(jsonPath("$.tips", hasSize(10)))
                .andExpect(jsonPath("$.tips[0].code").value("ABOUT"))
                .andExpect(jsonPath("$.tips[0].done").value(false))
                .andExpect(jsonPath("$.tips[0].weight").value(15));

        putProfile(token, "{\"aboutText\": \"Люблю долгие прогулки, плёночную фотографию и хороший кофе\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.completenessPercent").value(70))
                .andExpect(jsonPath("$.tips[0].code").value("MORE_PHOTOS"));
    }
}
