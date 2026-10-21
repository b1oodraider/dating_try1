package com.dating;

import com.dating.achievements.application.AchievementService;
import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.errors.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Achievement rewards and the premium composition are edited at runtime and take effect without a restart.
 * The premium test always resets the composition to the test-profile config at the end.
 */
class AdminEconomyIntegrationTest extends AdminTestSupport {

    @Autowired
    AchievementService achievementService;

    @Autowired
    FeatureAccess featureAccess;

    @Test
    void achievementDefinitionsAreEditable() throws Exception {
        String editor = editorToken();
        String code = "QA_ACH_" + suffix();
        jdbc.update("INSERT INTO achievement_definitions (code, title, description, rewards) VALUES (?, 'Старое', NULL, "
                + "'[{\"item\": \"TEST_SUPERLIKE\", \"quantity\": 1}]')", code);

        as(editor, get("/api/v1/admin/achievements"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItems("STUB_COLLECTOR", code)))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].rewards[0].itemCode", contains("TEST_SUPERLIKE")));

        as(editor, put("/api/v1/admin/achievements/" + code), """
                {"title": "Новое", "description": "Описание", "active": true,
                 "rewards": [{"type": "ITEM", "itemCode": "GACHA_TICKET", "quantity": 3},
                             {"type": "ITEM", "itemCode": "TEST_FRAME"},
                             {"type": "PREMIUM_DAYS", "days": 2}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Новое"))
                .andExpect(jsonPath("$.rewards", hasSize(3)))
                .andExpect(jsonPath("$.completionCount").value(0));

        // Invalid rewards are rejected and nothing changes.
        String bad = """
                {"title": "x", "active": true, "rewards": [%s]}""";
        as(editor, put("/api/v1/admin/achievements/" + code), bad.formatted("{\"type\": \"ITEM\", \"itemCode\": \"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['rewards[0].itemCode']").exists());
        as(editor, put("/api/v1/admin/achievements/" + code), bad.formatted("{\"type\": \"ITEM\", \"itemCode\": \"TEST_FRAME\", \"quantity\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['rewards[0].quantity']").exists());
        as(editor, put("/api/v1/admin/achievements/" + code), bad.formatted("{\"type\": \"PREMIUM_DAYS\", \"days\": 0}"))
                .andExpect(status().isBadRequest());
        as(editor, put("/api/v1/admin/achievements/" + code), bad.formatted("{\"type\": \"GEMS\"}"))
                .andExpect(status().isBadRequest());
        as(editor, put("/api/v1/admin/achievements/NO_SUCH"), bad.formatted("")).andExpect(status().isNotFound());

        // New rewards apply to completions from now on.
        String user = login(newPhone());
        UUID account = UUID.fromString(accountId(user));
        assertTrue(achievementService.complete(account, code));
        as(user, get("/api/v1/me/wallet"), null)
                .andExpect(jsonPath("$.premium.active").value(true))
                // GACHA_TICKET is not a premium allowance in the test profile, so all 3 come from the achievement.
                .andExpect(jsonPath("$.items[?(@.code == 'GACHA_TICKET')].quantity", contains(3)))
                .andExpect(jsonPath("$.items[?(@.code == 'TEST_FRAME')].quantity", contains(1)))
                // The granted premium tops up the TEST_SUPERLIKE allowance (5, all periodic); the old 1-unit
                // reward would show up as a non-periodic extra.
                .andExpect(jsonPath("$.items[?(@.code == 'TEST_SUPERLIKE')].quantity", contains(5)))
                .andExpect(jsonPath("$.items[?(@.code == 'TEST_SUPERLIKE')].periodicQuantity", contains(5)));
        as(user, get("/api/v1/me/achievements"), null)
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].title", contains("Новое")));

        // Hidden achievements disappear for users.
        as(editor, put("/api/v1/admin/achievements/" + code), """
                {"title": "Новое", "active": false, "rewards": []}""")
                .andExpect(jsonPath("$.completionCount").value(1));
        as(user, get("/api/v1/me/achievements"), null).andExpect(jsonPath("$[*].code", not(hasItem(code))));

        admin(get("/api/v1/admin/audit").queryParam("entityType", "ACHIEVEMENT").queryParam("entityId", code), null)
                .andExpect(jsonPath("$[*].action", contains("UPDATE", "UPDATE")))
                .andExpect(jsonPath("$[1].before.title").value("Старое"))
                .andExpect(jsonPath("$[1].after.rewards[2].days").value(2));
    }

    @Test
    void premiumCompositionChangesTakeEffectWithoutRestart() throws Exception {
        String free = login(newPhone());
        UUID freeId = UUID.fromString(accountId(free));
        try {
            admin(get("/api/v1/admin/premium"), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current.features", contains("TEST_PREMIUM_FEATURE", "ADVANCED_FILTERS", "EXCLUSION_WORDS",
                            "UNDO_SWIPE", "PASS_REFRESH", "INVISIBLE")))
                    .andExpect(jsonPath("$.current.featuresOverridden").value(false))
                    .andExpect(jsonPath("$.defaults.allowances[*].itemCode", contains("TEST_SUPERLIKE", "TEST_TICKET")));
            assertFalse(featureAccess.isAllowed(freeId, "TEST_PREMIUM_FEATURE"));

            // TEST_PREMIUM_FEATURE becomes free, NEW_FEATURE becomes premium: immediately, no restart.
            admin(put("/api/v1/admin/premium"), """
                    {"features": ["ADVANCED_FILTERS", "EXCLUSION_WORDS", "NEW_FEATURE"]}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current.featuresOverridden").value(true))
                    .andExpect(jsonPath("$.current.allowancesOverridden").value(false));
            assertTrue(featureAccess.isAllowed(freeId, "TEST_PREMIUM_FEATURE"));
            ApiException error = assertThrows(ApiException.class, () -> featureAccess.require(freeId, "NEW_FEATURE"));
            assertEquals("PREMIUM_REQUIRED", error.getCode());
            as(free, get("/api/v1/me/wallet"), null)
                    .andExpect(jsonPath("$.premiumFeatures", contains("ADVANCED_FILTERS", "EXCLUSION_WORDS", "NEW_FEATURE")));

            // Allowances: only 2 TEST_SUPERLIKE a week now, no tickets.
            admin(put("/api/v1/admin/premium"), """
                    {"allowances": [{"itemCode": "TEST_SUPERLIKE", "quantity": 2, "period": "WEEKLY", "mode": "TOP_UP"}]}""")
                    .andExpect(jsonPath("$.current.allowances", hasSize(1)))
                    .andExpect(jsonPath("$.current.features", hasItem("NEW_FEATURE")));
            String premiumUser = login(newPhone());
            grantPremium(premiumUser, 7);
            as(premiumUser, get("/api/v1/me/wallet"), null)
                    .andExpect(jsonPath("$.items[?(@.code == 'TEST_SUPERLIKE')].periodicQuantity", contains(2)))
                    .andExpect(jsonPath("$.items[?(@.code == 'TEST_TICKET')]", hasSize(0)));

            admin(put("/api/v1/admin/premium"), """
                    {"allowances": [{"itemCode": "FRAME_STUB_GOLD", "quantity": 1, "period": "WEEKLY", "mode": "ADD"}]}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors['allowances[0].itemCode']").exists());
            admin(put("/api/v1/admin/premium"), """
                    {"allowances": [{"itemCode": "TEST_SUPERLIKE", "quantity": 0, "period": "WEEKLY", "mode": "ADD"}]}""")
                    .andExpect(status().isBadRequest());
            admin(put("/api/v1/admin/premium"), "{\"features\": [\"bad code\"]}").andExpect(status().isBadRequest());
            as(editorToken(), put("/api/v1/admin/premium"), "{\"features\": []}").andExpect(status().isForbidden());

            admin(delete("/api/v1/admin/premium"), null)
                    .andExpect(jsonPath("$.current.featuresOverridden").value(false))
                    .andExpect(jsonPath("$.current.features", hasItem("TEST_PREMIUM_FEATURE")));
            assertFalse(featureAccess.isAllowed(freeId, "TEST_PREMIUM_FEATURE"));
            assertTrue(featureAccess.isAllowed(freeId, "NEW_FEATURE"));

            admin(get("/api/v1/admin/audit").queryParam("entityType", "PREMIUM_COMPOSITION").queryParam("limit", "3"), null)
                    .andExpect(jsonPath("$[*].action", contains("RESET", "UPDATE", "UPDATE")))
                    .andExpect(jsonPath("$[0].before.features", hasItem("NEW_FEATURE")));
        } finally {
            admin(delete("/api/v1/admin/premium"), null).andExpect(status().isOk());
        }
    }
}
