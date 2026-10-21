package com.dating;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FAQ: public list of active answers, edited by CONTENT_EDITOR / SUPPORT / ADMIN. */
class FaqIntegrationTest extends AdminTestSupport {

    @Test
    void seededFaqIsPublicAndEditorsManageIt() throws Exception {
        mvc.perform(get("/api/v1/faq")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("Куда пропадают лайки?"));

        String user = login(newPhone());
        as(user, get("/api/v1/admin/faq"), null).andExpect(status().isForbidden());

        String support = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(support) + "/roles/SUPPORT"), null).andExpect(status().isOk());
        as(support, post("/api/v1/admin/faq"), "{\"question\": \"\", \"answer\": \"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.question").exists());
        String id = json(as(support, post("/api/v1/admin/faq"),
                "{\"question\": \"Как удалить аккаунт?\", \"answer\": \"Профиль → Настройки\", \"sortOrder\": 1}")
                .andExpect(result -> {
                    // Shows the server's own exception when this fails (CI keeps no logs of the app).
                    if (result.getResponse().getStatus() != 201) {
                        Exception cause = result.getResolvedException();
                        throw new AssertionError("Expected 201, got " + result.getResponse().getStatus() + ": "
                                + result.getResponse().getContentAsString(), cause);
                    }
                }), "$.id");
        mvc.perform(get("/api/v1/faq")).andExpect(jsonPath("$[0].question").value("Как удалить аккаунт?"));

        as(support, put("/api/v1/admin/faq/" + id),
                "{\"question\": \"Как удалить аккаунт?\", \"answer\": \"Профиль → Настройки\", \"active\": false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/faq")).andExpect(jsonPath("$[*].question", not(hasItem("Как удалить аккаунт?"))));
        as(support, get("/api/v1/admin/faq"), null).andExpect(jsonPath("$[*].id", hasItem(id)));

        as(support, delete("/api/v1/admin/faq/" + id), null).andExpect(status().isNoContent());
        as(support, delete("/api/v1/admin/faq/" + id), null).andExpect(status().isNotFound());
    }

    private static String json(org.springframework.test.web.servlet.ResultActions actions, String path)
            throws Exception {
        return com.jayway.jsonpath.JsonPath.read(body(actions.andReturn()), path).toString();
    }
}
