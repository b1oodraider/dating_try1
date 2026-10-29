package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Interest groups (docs/communities.md): catalogue, subscriptions, search among members, suggestions, staff. */
class CommunitiesIntegrationTest extends AdminTestSupport {

    @Test
    void membersOfAGroupFindEachOtherAndSeeItInCommon() throws Exception {
        String city = uniqueCity();
        String anna = login(newPhone());
        publishWith(anna, "Анна", city, List.of());
        String boris = login(newPhone());
        publishWith(boris, "Борис", city, List.of());
        String vera = login(newPhone());
        publishWith(vera, "Вера", city, List.of());

        long potter = idOf(anna, "Гарри Поттер");
        long rings = idOf(anna, "Властелин колец");
        as(anna, get("/api/v1/communities/" + potter), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FRANCHISE"))
                .andExpect(jsonPath("$.kindLabel").value("Вселенные"))
                .andExpect(jsonPath("$.emoji").value("⚡"))
                .andExpect(jsonPath("$.subscribed").value(false));

        as(anna, put("/api/v1/communities/" + potter + "/subscription"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.members").value(greaterThanOrEqualTo(1)));
        as(anna, put("/api/v1/communities/" + potter + "/subscription"), null).andExpect(status().isOk()); // again: no-op
        as(boris, put("/api/v1/communities/" + potter + "/subscription"), null).andExpect(status().isOk());
        as(boris, put("/api/v1/communities/" + rings + "/subscription"), null).andExpect(status().isOk());
        as(vera, put("/api/v1/communities/" + rings + "/subscription"), null).andExpect(status().isOk());
        as(boris, get("/api/v1/me/communities"), null)
                .andExpect(jsonPath("$[*].title", containsInAnyOrder("Гарри Поттер", "Властелин колец")));
        as(anna, get("/api/v1/communities").queryParam("q", "поттер"), null)
                .andExpect(jsonPath("$[0].title").value("Гарри Поттер"));
        as(anna, get("/api/v1/communities").queryParam("kind", "BOOK"), null)
                .andExpect(jsonPath("$[*].kind", everyItem(is("BOOK"))));
        as(anna, get("/api/v1/communities").queryParam("kind", "NOPE"), null).andExpect(status().isBadRequest());
        as(anna, get("/api/v1/communities/999999"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        // Search among the group: Борис (a member), not Вера.
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(anna))
                        .queryParam("city", city).queryParam("communityIds", String.valueOf(potter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].displayName", contains("Борис")))
                .andExpect(jsonPath("$.items[0].common[0]").value("Гарри Поттер"))
                .andExpect(jsonPath("$.items[0].communities[0].title").value("Гарри Поттер"))
                .andExpect(jsonPath("$.items[0].communities[0].shared").value(true))
                .andExpect(jsonPath("$.items[0].communities[1].shared").value(false));
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(anna))
                        .queryParam("city", city)
                        .queryParam("communityIds", String.valueOf(potter), String.valueOf(rings)))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Борис", "Вера")));

        as(boris, delete("/api/v1/communities/" + potter + "/subscription"), null)
                .andExpect(jsonPath("$.subscribed").value(false));
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(anna))
                        .queryParam("city", city).queryParam("communityIds", String.valueOf(potter)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void usersSuggestGroupsAndStaffManageThem() throws Exception {
        String user = login(newPhone());
        String editor = editorToken();
        as(user, post("/api/v1/communities/suggestions"), "{\"title\": \"Сейлор Мун\", \"comment\": \"аниме 90-х\"}")
                .andExpect(status().isCreated());
        as(user, post("/api/v1/communities/suggestions"), "{\"title\": \" \"}").andExpect(status().isBadRequest());

        as(user, get("/api/v1/admin/communities/suggestions"), null).andExpect(status().isForbidden());
        String suggestions = body(as(editor, get("/api/v1/admin/communities/suggestions"), null)
                .andExpect(status().isOk()).andReturn());
        List<String> ids = JsonPath.read(suggestions, "$[?(@.title == 'Сейлор Мун')].id");
        as(editor, post("/api/v1/admin/communities/suggestions/" + ids.getFirst() + "/decision"), "{\"accepted\": true}")
                .andExpect(status().isNoContent());
        as(editor, post("/api/v1/admin/communities/suggestions/" + ids.getFirst() + "/decision"), "{\"accepted\": true}")
                .andExpect(status().isNotFound());

        as(user, post("/api/v1/admin/communities"), "{\"slug\": \"sailor-moon\", \"title\": \"Сейлор Мун\", \"kind\": \"ANIME\"}")
                .andExpect(status().isForbidden());
        as(editor, post("/api/v1/admin/communities"), "{\"slug\": \"Плохой адрес\", \"title\": \"Х\", \"kind\": \"NOPE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.slug").exists())
                .andExpect(jsonPath("$.fieldErrors.kind").exists());
        String created = body(as(editor, post("/api/v1/admin/communities"),
                "{\"slug\": \"sailor-moon\", \"title\": \"Сейлор Мун\", \"kind\": \"ANIME\", \"emoji\": \"🌙\"}")
                .andExpect(status().isCreated()).andReturn());
        Integer id = JsonPath.read(created, "$.id");
        as(user, get("/api/v1/communities").queryParam("q", "сейлор"), null)
                .andExpect(jsonPath("$[0].emoji").value("🌙"));
        as(editor, put("/api/v1/admin/communities/" + id),
                "{\"title\": \"Сейлор Мун\", \"kind\": \"ANIME\", \"active\": false}").andExpect(status().isOk());
        as(user, get("/api/v1/communities/" + id), null).andExpect(status().isNotFound());
    }

    private long idOf(String token, String title) throws Exception {
        String list = body(as(token, get("/api/v1/communities").queryParam("q", title), null)
                .andExpect(status().isOk()).andReturn());
        List<Integer> ids = JsonPath.read(list, "$[?(@.title == '" + title + "')].id");
        return ids.getFirst();
    }
}
