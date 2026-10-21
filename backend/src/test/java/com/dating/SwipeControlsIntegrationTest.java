package com.dating;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Undo, hide, bring passes back, invisible mode, premium badge toggle, inactive profiles out of the feed. */
class SwipeControlsIntegrationTest extends IntegrationTestBase {

    @Test
    void undoNeedsPremiumAndReturnsTheLastProfile() throws Exception {
        String city = uniqueCity();
        published("Анна", city);
        published("Вера", city);
        String me = login(newPhone());
        String anna = idOf(me, city, "Анна");
        String vera = idOf(me, city, "Вера");

        post(me, "/api/v1/me/reactions/undo").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.feature").value("UNDO_SWIPE"));

        grantPremium(me, 30);
        post(me, "/api/v1/me/reactions/undo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_UNDO"));

        post(me, "/api/v1/discovery/profiles/" + anna + "/pass").andExpect(status().isOk());
        post(me, "/api/v1/discovery/profiles/" + vera + "/like").andExpect(status().isOk());
        search(me, city).andExpect(jsonPath("$.totalElements").value(0));

        post(me, "/api/v1/me/reactions/undo").andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Вера"));
        post(me, "/api/v1/me/reactions/undo").andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Анна"));
        search(me, city).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void undoLimitInARow() throws Exception {
        String city = uniqueCity();
        for (String name : List.of("Анна", "Вера", "Галя", "Даша", "Ева", "Жанна")) {
            published(name, city);
        }
        String me = login(newPhone());
        grantPremium(me, 30);
        canSearch(me);
        for (int i = 0; i < 6; i++) {
            String id = json(get("/api/v1/discovery/profiles").queryParam("city", city), me, "$.items[0].id");
            post(me, "/api/v1/discovery/profiles/" + id + "/pass").andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            post(me, "/api/v1/me/reactions/undo").andExpect(status().isOk());
        }
        post(me, "/api/v1/me/reactions/undo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNDO_LIMIT_REACHED"));

        // A new swipe resets the count.
        String id = json(get("/api/v1/discovery/profiles").queryParam("city", city), me, "$.items[0].id");
        post(me, "/api/v1/discovery/profiles/" + id + "/pass").andExpect(status().isOk());
        post(me, "/api/v1/me/reactions/undo").andExpect(status().isOk());
    }

    @Test
    void likeThatMadeAMatchCannotBeUndone() throws Exception {
        String city = uniqueCity();
        String anna = published("Анна", city);
        String boris = published("Борис", city);
        grantPremium(boris, 30);
        post(anna, "/api/v1/discovery/profiles/" + idOf(anna, city, "Борис") + "/like").andExpect(status().isOk());
        post(boris, "/api/v1/discovery/profiles/" + idOf(boris, city, "Анна") + "/like")
                .andExpect(jsonPath("$.matched").value(true));
        post(boris, "/api/v1/me/reactions/undo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_UNDO"));
    }

    @Test
    void freeRefreshOnlyWhenFeedIsEmptyOncePerDay() throws Exception {
        String city = uniqueCity();
        published("Анна", city);
        String me = login(newPhone());
        post(me, "/api/v1/discovery/profiles/" + idOf(me, city, "Анна") + "/pass").andExpect(status().isOk());

        post(me, "/api/v1/me/passes/refresh").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEED_NOT_EMPTY"));
        post(me, "/api/v1/me/passes/refresh?feedEmpty=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1));
        search(me, city).andExpect(jsonPath("$.totalElements").value(1));
        post(me, "/api/v1/me/passes/refresh?feedEmpty=true").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASS_REFRESH_TOO_SOON"));

        grantPremium(me, 30);
        post(me, "/api/v1/me/passes/refresh").andExpect(status().isOk());
    }

    @Test
    void hiddenProfileLeavesOnlyMyFeed() throws Exception {
        String city = uniqueCity();
        String anna = published("Анна", city);
        String me = login(newPhone());
        String annaId = idOf(me, city, "Анна");

        post(me, "/api/v1/accounts/" + annaId + "/hide").andExpect(status().isNoContent());
        post(me, "/api/v1/accounts/" + annaId + "/hide").andExpect(status().isNoContent());
        search(me, city).andExpect(jsonPath("$.totalElements").value(0));

        // Anna is not affected and still sees me.
        publishWith(me, "Борис", city, List.of());
        search(anna, city).andExpect(jsonPath("$.items[*].displayName", contains("Борис")));
    }

    @Test
    void invisibleProfileIsShownOnlyToLikedPeopleWhilePremium() throws Exception {
        String city = uniqueCity();
        String ghost = published("Призрак", city);
        putProfile(ghost, "{\"invisible\": true}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.feature").value("INVISIBLE"));
        grantPremium(ghost, 30);
        putProfile(ghost, "{\"invisible\": true, \"showPremiumBadge\": false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.invisible").value(true))
                .andExpect(jsonPath("$.showPremiumBadge").value(false));

        String stranger = login(newPhone());
        search(stranger, city).andExpect(jsonPath("$.totalElements").value(0));

        String liked = published("Избранный", city);
        post(ghost, "/api/v1/discovery/profiles/" + idOf(ghost, city, "Избранный") + "/like").andExpect(status().isOk());
        search(liked, city)
                .andExpect(jsonPath("$.items[*].displayName", contains("Призрак")))
                .andExpect(jsonPath("$.items[0].premium").value(false)); // badge hidden

        economyService.expirePremium(UUID.fromString(json(get("/api/v1/me"), ghost, "$.id")));
        search(stranger, city).andExpect(jsonPath("$.items[*].displayName", hasItem("Призрак")));
    }

    @Test
    void inactiveProfilesLeaveTheFeed() throws Exception {
        String city = uniqueCity();
        String sleeper = published("Спящий", city);
        UUID sleeperId = UUID.fromString(json(get("/api/v1/me"), sleeper, "$.id"));
        jdbc.update("UPDATE profiles SET created_at = now() - interval '40 days' WHERE account_id = ?", sleeperId);
        jdbc.update("DELETE FROM account_activity_days WHERE account_id = ?", sleeperId);
        jdbc.update("INSERT INTO account_activity_days (account_id, day) VALUES (?, current_date - 31)", sleeperId);

        String me = login(newPhone());
        search(me, city).andExpect(jsonPath("$.totalElements").value(0));

        jdbc.update("INSERT INTO account_activity_days (account_id, day) VALUES (?, current_date - 29)", sleeperId);
        search(me, city).andExpect(jsonPath("$.totalElements").value(1));
    }

    private String published(String name, String city) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return token;
    }

    private ResultActions search(String token, String city) throws Exception {
        canSearch(token);
        return mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(token))
                .queryParam("city", city)).andExpect(status().isOk());
    }

    private String idOf(String viewer, String city, String name) throws Exception {
        canSearch(viewer);
        String page = body(mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city)
                .header("Authorization", bearer(viewer))).andExpect(status().isOk()).andReturn());
        List<String> ids = com.jayway.jsonpath.JsonPath.read(page, "$.items[?(@.displayName == '" + name + "')].id");
        return ids.getFirst();
    }

    private ResultActions post(String token, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", bearer(token)));
    }
}
