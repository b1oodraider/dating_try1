package com.dating;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Percent match by cards next to every profile the viewer sees; editors order cards on scales. */
class CompatibilityIntegrationTest extends AdminTestSupport {

    record User(String token, String profileId) {
    }

    @Test
    void feedAndReceivedLikesShowTheMatchByCards() throws Exception {
        String city = uniqueCity();
        User anna = user(city, "Анна", List.of("PACE_ASYNCHRONOUS", "FORMAT_WALK_FIRST", "CALLS_TEXT_ONLY"));
        // pace: 1 vs 2 on a 1..3 scale → 0.5; format: same card → 1; calls: opposite ends → 0  ⇒ 50 %
        User boris = user(city, "Борис", List.of("PACE_LOW_BATTERY", "FORMAT_WALK_FIRST", "CALLS_VOICE_WELCOME"));
        User twin = user(city, "Близнец", List.of("PACE_ASYNCHRONOUS", "FORMAT_WALK_FIRST", "CALLS_TEXT_ONLY"));
        User stranger = user(city, "Незнакомец", List.of("PACE_FAST_CHAT")); // one shared category: no percent

        discovery(anna.token(), city, null, null, 50)
                .andExpect(jsonPath("$.items[?(@.id == '" + boris.profileId() + "')].compatibility", contains(50)))
                .andExpect(jsonPath("$.items[?(@.id == '" + twin.profileId() + "')].compatibility", contains(100)))
                .andExpect(jsonPath("$.items[?(@.id == '" + stranger.profileId() + "')].compatibility", hasItem((Object) null)));

        as(boris.token(), post("/api/v1/discovery/profiles/" + anna.profileId() + "/like"), null).andExpect(status().isOk());
        as(anna.token(), get("/api/v1/me/likes/received"), null)
                .andExpect(jsonPath("$.items[0].profile.compatibility").value(50));

        as(anna.token(), get("/api/v1/tags"), null)
                .andExpect(jsonPath("$[?(@.code == 'PACE_FAST_CHAT')].scalePosition", contains(3)));
    }

    @Test
    void editorsOrderCardsOnAScale() throws Exception {
        String editor = editorToken();
        String scale = "/api/v1/admin/tag-categories/social/scale";
        as(editor, put(scale), """
                {"scale": true, "scaleMinLabel": "Тишина", "scaleMaxLabel": "Разговоры",
                 "positions": {"SOC_QUIET_SPACES": 1, "SOC_DEEP_TALKS": 2}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scale").value(true))
                .andExpect(jsonPath("$.scaleMaxLabel").value("Разговоры"));
        as(editor, get("/api/v1/admin/tags"), null)
                .andExpect(jsonPath("$[?(@.code == 'SOC_DEEP_TALKS')].scalePosition", contains(2)));

        as(editor, put(scale), "{\"scale\": true, \"scaleMinLabel\": \"a\", \"scaleMaxLabel\": \"b\", "
                + "\"positions\": {\"SOC_QUIET_SPACES\": 1, \"PACE_FAST_CHAT\": 2}}").andExpect(status().isBadRequest());
        as(editor, put(scale), "{\"scale\": true, \"positions\": {\"SOC_QUIET_SPACES\": 1, \"SOC_DEEP_TALKS\": 2}}")
                .andExpect(status().isBadRequest());
        as(editor, put("/api/v1/admin/tag-categories/nope/scale"), "{\"scale\": false}").andExpect(status().isNotFound());
        as(login(newPhone()), put(scale), "{\"scale\": false}").andExpect(status().isForbidden());

        as(editor, put(scale), "{\"scale\": false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.scale").value(false));
        as(editor, get("/api/v1/admin/tags"), null)
                .andExpect(jsonPath("$[?(@.code == 'SOC_DEEP_TALKS')].scalePosition", hasItem((Object) null)));
    }

    private User user(String city, String name, List<String> tags) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, tags);
        return new User(token, json(get("/api/v1/me/profile"), token, "$.id"));
    }
}
