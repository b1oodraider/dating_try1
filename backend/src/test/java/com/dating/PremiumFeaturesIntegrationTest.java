package com.dating;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Premium: height/goals in profiles, advanced search (ADVANCED_FILTERS), excluded goals,
 * exclusion words (EXCLUSION_WORDS), premium badge and behaviour after premium ends.
 */
class PremiumFeaturesIntegrationTest extends IntegrationTestBase {

    @Test
    void goalsReferenceAndProfileValidation() throws Exception {
        mvc.perform(get("/api/v1/goals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItems("SERIOUS_RELATIONSHIP", "FRIENDSHIP", "NOT_SURE")));

        String token = login(newPhone());
        putProfile(token, "{\"heightCm\": 182, \"goalCodes\": [\"FRIENDSHIP\", \"SERIOUS_RELATIONSHIP\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heightCm").value(182))
                .andExpect(jsonPath("$.goals[*].code", contains("SERIOUS_RELATIONSHIP", "FRIENDSHIP")))
                .andExpect(jsonPath("$.premium").value(false));

        putProfile(token, "{\"goalCodes\": [\"NOPE\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_GOAL"));
        putProfile(token, "{\"goalCodes\": [\"DATING\", \"CHAT\", \"FAMILY\", \"FRIENDSHIP\"]}").andExpect(status().isBadRequest());
        putProfile(token, "{\"heightCm\": 300}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.heightCm").exists());

        putProfile(token, "{\"heightCm\": 170, \"clearHeight\": true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.heightCm").exists());
        putProfile(token, "{\"clearHeight\": true, \"goalCodes\": []}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heightCm").value(nullValue()))
                .andExpect(jsonPath("$.goals", hasSize(0)));
    }

    @Test
    void premiumFeaturesAreClosedForFreeUsers() throws Exception {
        String token = login(newPhone());
        canSearch(token);

        mvc.perform(get("/api/v1/discovery/profiles").queryParam("minHeight", "170").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PREMIUM_REQUIRED"))
                .andExpect(jsonPath("$.details.feature").value("ADVANCED_FILTERS"));
        // Goals are a free filter now (docs/matching.md).
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("goalCodes", "DATING").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        putWords(token, "[\"курить\"]").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.feature").value("EXCLUSION_WORDS"));
        putExcludedGoals(token, "[\"DATING\"]").andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/me/exclusion-words").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.active").value(false));
        // Basic filters stay free.
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("minAge", "18").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void advancedSearchByHeightGoalsAndExcludedGoals() throws Exception {
        String city = uniqueCity();
        published("Высокий", city, "{\"heightCm\": 185, \"goalCodes\": [\"SERIOUS_RELATIONSHIP\"]}");
        published("Невысокий", city, "{\"heightCm\": 160, \"goalCodes\": [\"FRIENDSHIP\"]}");
        published("БезРоста", city, "{\"goalCodes\": [\"DATING\", \"SERIOUS_RELATIONSHIP\"]}");
        published("БезЦелей", city, "{\"heightCm\": 175}");

        String viewer = login(newPhone());
        grantPremium(viewer, 30);

        search(viewer, city, "minHeight", "170")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Высокий", "БезЦелей")));
        search(viewer, city, "goalCodes", "SERIOUS_RELATIONSHIP")
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Высокий", "БезРоста")));

        putExcludedGoals(viewer, "[\"DATING\", \"FRIENDSHIP\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        search(viewer, city, null, null)
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Высокий", "БезЦелей")));

        putExcludedGoals(viewer, "[\"NOPE\"]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_GOAL"));
    }

    @Test
    void premiumBadgeAndSavedFiltersAfterPremiumEnds() throws Exception {
        String city = uniqueCity();
        String premiumOwner = published("Премиум", city, "{\"goalCodes\": [\"DATING\"]}");
        grantPremium(premiumOwner, 30);
        published("Обычный", city, "{\"aboutText\": \"Курю иногда\"}");

        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(premiumOwner)))
                .andExpect(jsonPath("$.premium").value(true));

        String viewer = login(newPhone());
        grantPremium(viewer, 30);
        search(viewer, city, null, null)
                .andExpect(jsonPath("$.items[?(@.displayName == 'Премиум')].premium", contains(true)))
                .andExpect(jsonPath("$.items[?(@.displayName == 'Обычный')].premium", contains(false)));

        putExcludedGoals(viewer, "[\"DATING\"]").andExpect(status().isOk());
        putWords(viewer, "[\"курить\"]").andExpect(status().isOk());
        search(viewer, city, null, null).andExpect(jsonPath("$.totalElements").value(0));

        // Premium ends: saved lists are kept but no longer applied.
        UUID viewerId = UUID.fromString(json(get("/api/v1/me"), viewer, "$.id"));
        economyService.expirePremium(viewerId);
        search(viewer, city, null, null).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/me/excluded-goals").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.goalCodes", contains("DATING")))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/me/exclusion-words").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.words", contains("курить")))
                .andExpect(jsonPath("$.active").value(false));
    }

    // ------------------------------------------------------------------ helpers

    private String published(String name, String city, String extraProfileJson) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        putProfile(token, extraProfileJson).andExpect(status().isOk());
        return token;
    }

    private ResultActions search(String token, String city, String param, String value) throws Exception {
        canSearch(token);
        var request = get("/api/v1/discovery/profiles").header("Authorization", bearer(token)).queryParam("city", city);
        if (param != null) {
            request.queryParam(param, value);
        }
        return mvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions putExcludedGoals(String token, String codesJson) throws Exception {
        return mvc.perform(put("/api/v1/me/excluded-goals")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goalCodes\": " + codesJson + "}"));
    }
}
