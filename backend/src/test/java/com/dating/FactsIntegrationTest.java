package com.dating;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Profile facts, two-way fact filters with «show those who did not say», interests in the feed score. */
class FactsIntegrationTest extends AdminTestSupport {

    @Test
    void optionsAreServedAndFactsAreValidatedAndShown() throws Exception {
        mvc.perform(get("/api/v1/profile-options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.smoking[*].code", contains("NO", "SOMETIMES", "CIGARETTES", "VAPE")))
                .andExpect(jsonPath("$.languages[0].label").value("Русский"));

        String city = uniqueCity();
        String me = login(newPhone());
        publishWith(me, "Факт", city, List.of());
        putProfile(me, "{\"smoking\": \"PIPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.smoking").exists());
        putProfile(me, "{\"pets\": [\"NONE\", \"CAT\"]}").andExpect(status().isBadRequest());
        putProfile(me, "{\"smoking\": \"VAPE\", \"pets\": [\"CAT\", \"DOG\"], \"languages\": [\"RU\", \"EN\"],"
                + " \"profession\": \"Дизайнер\", \"educationLevel\": \"HIGHER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.smoking").value("VAPE"))
                .andExpect(jsonPath("$.facts.pets", contains("CAT", "DOG")))
                .andExpect(jsonPath("$.facts.profession").value("Дизайнер"));
        putProfile(me, "{\"smoking\": \"\", \"pets\": []}").andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.smoking").value(nullValue()))
                .andExpect(jsonPath("$.facts.pets", hasSize(0)));

        String viewer = login(newPhone());
        feed(viewer, city).andExpect(jsonPath("$.items[0].facts.languages", contains("RU", "EN")));
    }

    @Test
    void factFiltersWorkBothWaysAndCanShowThoseWhoDidNotSay() throws Exception {
        String city = uniqueCity();
        String smoker = login(newPhone());
        publishWith(smoker, "Курит", city, List.of());
        putProfile(smoker, "{\"smoking\": \"CIGARETTES\"}").andExpect(status().isOk());
        String quiet = login(newPhone());
        publishWith(quiet, "Молчит", city, List.of());
        String clean = login(newPhone());
        publishWith(clean, "Не курит", city, List.of());
        putProfile(clean, "{\"smoking\": \"NO\"}").andExpect(status().isOk());

        String me = login(newPhone());
        publishWith(me, "Смотрящий", city, List.of());
        filters(me, "{\"smoking\": {\"accept\": [\"NO\"], \"unspecified\": false}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.smoking.accept", contains("NO")));
        feed(me, city).andExpect(jsonPath("$.items[*].displayName", contains("Не курит")));

        filters(me, "{\"smoking\": {\"accept\": [\"NO\"], \"unspecified\": true}}").andExpect(status().isOk());
        feed(me, city).andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Не курит", "Молчит")));

        // Two-way: I have not said whether I smoke, the non-smoker hides those who did not say.
        filters(clean, "{\"smoking\": {\"accept\": [\"NO\"], \"unspecified\": false}}").andExpect(status().isOk());
        feed(me, city).andExpect(jsonPath("$.items[*].displayName", contains("Молчит")));

        filters(me, "{\"smoking\": {\"accept\": []}}").andExpect(status().isBadRequest());
        filters(me, "{\"religion\": {\"accept\": [\"X\"]}}").andExpect(status().isBadRequest());
        filters(me, "{}").andExpect(status().isOk());
        feed(me, city).andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Курит", "Молчит")));
    }

    @Test
    void sharedInterestsAndArtistsRaiseTheScore() throws Exception {
        String city = uniqueCity();
        String viewer = login(newPhone());
        publishWith(viewer, "Зритель", city, List.of());
        putProfile(viewer, "{\"interestCodes\": [\"RUNNING\", \"COFFEE\"]}").andExpect(status().isOk());
        String twin = login(newPhone());
        publishWith(twin, "Близнец", city, List.of());
        putProfile(twin, "{\"interestCodes\": [\"RUNNING\", \"TEA\"]}").andExpect(status().isOk());

        as(adminToken(), get("/api/v1/admin/discovery/score")
                .queryParam("viewerAccountId", accountId(viewer))
                .queryParam("candidateProfileId", json(get("/api/v1/me/profile"), twin, "$.id")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests").value(4.0)) // RUNNING 3 + the same category (food) 1
                .andExpect(jsonPath("$.behavior").value(0.0));
    }

    @Test
    void oneCommunicationCardPerCategory() throws Exception {
        String me = login(newPhone());
        putProfile(me, "{\"tagCodes\": [\"PACE_ASYNCHRONOUS\", \"PACE_FAST_CHAT\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONE_TAG_PER_CATEGORY"))
                .andExpect(jsonPath("$.fieldErrors.tagCodes", containsString("Темп переписки")));
        putProfile(me, "{\"tagCodes\": [\"PACE_FAST_CHAT\", \"FORMAT_WALK_FIRST\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(2)));
    }

    private ResultActions feed(String token, String city) throws Exception {
        canSearch(token);
        return mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city)
                .header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    private ResultActions filters(String token, String body) throws Exception {
        return as(token, put("/api/v1/me/fact-filters"), body);
    }
}
