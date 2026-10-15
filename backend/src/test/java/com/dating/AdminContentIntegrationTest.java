package com.dating;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Communication cards, their categories and relationship goals edited through the admin API:
 * immediate effect on the public lists, "hide instead of delete" for used items, audit trail.
 */
class AdminContentIntegrationTest extends AdminTestSupport {

    @Test
    void tagsAndCategoriesCrudWithAudit() throws Exception {
        String editor = editorToken();
        String category = "qa_" + suffix().toLowerCase(Locale.ROOT);
        String tag = "QA_TAG_" + suffix();
        String unused = "QA_TAG_" + suffix();

        as(editor, post("/api/v1/admin/tag-categories"), """
                {"code": "%s", "label": "Тестовая категория", "sortOrder": 900}""".formatted(category))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tagCount").value(0));
        as(editor, post("/api/v1/admin/tag-categories"), """
                {"code": "%s", "label": "Дубль"}""".formatted(category))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));

        String tagJson = """
                {"code": "%s", "category": "%s", "label": "Пишу голосовые", "description": "Иногда отвечаю голосом", "sortOrder": 901}""";
        as(editor, post("/api/v1/admin/tags"), tagJson.formatted(tag, category))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(tag))
                .andExpect(jsonPath("$.categoryLabel").value("Тестовая категория"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.usageCount").value(0));
        as(editor, post("/api/v1/admin/tags"), tagJson.formatted(tag, category))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAG_EXISTS"));
        as(editor, post("/api/v1/admin/tags"), tagJson.formatted("QA_TAG_" + suffix(), "no_such_category"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));
        as(editor, post("/api/v1/admin/tags"), tagJson.formatted("bad code", category))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").exists());

        mvc.perform(get("/api/v1/tags"))
                .andExpect(jsonPath("$[?(@.code == '" + tag + "')].label", contains("Пишу голосовые")));

        // Edits show up in the public list right away; the category label is copied to its tags.
        as(editor, put("/api/v1/admin/tags/" + tag), """
                {"category": "%s", "label": "Люблю голосовые", "description": "Отвечаю голосом", "sortOrder": 902}"""
                .formatted(category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Люблю голосовые"));
        as(editor, put("/api/v1/admin/tag-categories/" + category), "{\"label\": \"Голос и звонки\", \"sortOrder\": 5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagCount").value(1));
        mvc.perform(get("/api/v1/tags"))
                .andExpect(jsonPath("$[?(@.code == '" + tag + "')].categoryLabel", contains("Голос и звонки")))
                .andExpect(jsonPath("$[?(@.code == '" + tag + "')].category", contains(category)));

        // Used by a profile: DELETE hides it (gone from /tags and from the profile), the row stays.
        String user = login(newPhone());
        putProfile(user, "{\"tagCodes\": [\"" + tag + "\", \"PACE_FAST_CHAT\"]}")
                .andExpect(jsonPath("$.tags[*].code", hasItem(tag)));
        as(editor, delete("/api/v1/admin/tags/" + tag), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEACTIVATED"));
        mvc.perform(get("/api/v1/tags")).andExpect(jsonPath("$[*].code", not(hasItem(tag))));
        as(user, get("/api/v1/me/profile"), null)
                .andExpect(jsonPath("$.tags[*].code", contains("PACE_FAST_CHAT")));
        putProfile(user, "{\"tagCodes\": [\"" + tag + "\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TAG"));
        as(editor, get("/api/v1/admin/tags"), null)
                .andExpect(jsonPath("$[?(@.code == '" + tag + "')].active", contains(false)))
                .andExpect(jsonPath("$[?(@.code == '" + tag + "')].usageCount", contains(1)));

        // Shown again with PUT active=true.
        as(editor, put("/api/v1/admin/tags/" + tag), """
                {"category": "%s", "label": "Люблю голосовые", "description": "Отвечаю голосом", "active": true}"""
                .formatted(category))
                .andExpect(jsonPath("$.active").value(true));
        as(user, get("/api/v1/me/profile"), null).andExpect(jsonPath("$.tags[*].code", hasItem(tag)));

        as(editor, delete("/api/v1/admin/tag-categories/" + category), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        // Unused: really deleted.
        as(editor, post("/api/v1/admin/tags"), tagJson.formatted(unused, category)).andExpect(status().isCreated());
        as(editor, delete("/api/v1/admin/tags/" + unused), null).andExpect(jsonPath("$.result").value("DELETED"));
        as(editor, get("/api/v1/admin/tags"), null).andExpect(jsonPath("$[*].code", not(hasItem(unused))));
        as(editor, delete("/api/v1/admin/tags/" + unused), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TAG_NOT_FOUND"));

        admin(get("/api/v1/admin/audit").queryParam("entityType", "TAG").queryParam("entityId", tag), null)
                .andExpect(jsonPath("$[*].action", contains("UPDATE", "DEACTIVATE", "UPDATE", "CREATE")))
                .andExpect(jsonPath("$[1].before.active").value(true))
                .andExpect(jsonPath("$[1].after.active").value(false))
                .andExpect(jsonPath("$[3].before").value(nullValue()))
                .andExpect(jsonPath("$[3].after.label").value("Пишу голосовые"));
        admin(get("/api/v1/admin/audit").queryParam("entityType", "TAG").queryParam("entityId", unused), null)
                .andExpect(jsonPath("$[*].action", contains("DELETE", "CREATE")))
                .andExpect(jsonPath("$[0].after").value(nullValue()));
        admin(get("/api/v1/admin/audit").queryParam("entityType", "TAG_CATEGORY").queryParam("entityId", category), null)
                .andExpect(jsonPath("$[*].action", contains("UPDATE", "CREATE")));

        // An empty category can be deleted.
        String empty = "qa_" + suffix().toLowerCase(Locale.ROOT);
        as(editor, post("/api/v1/admin/tag-categories"), "{\"code\": \"" + empty + "\", \"label\": \"Пусто\"}")
                .andExpect(status().isCreated());
        as(editor, delete("/api/v1/admin/tag-categories/" + empty), null).andExpect(status().isNoContent());
    }

    @Test
    void goalsCrudWithAudit() throws Exception {
        String editor = editorToken();
        String goal = "QA_GOAL_" + suffix();
        String unused = "QA_GOAL_" + suffix();

        as(editor, post("/api/v1/admin/goals"), "{\"code\": \"" + goal + "\", \"label\": \"Путешествия\", \"sortOrder\": 70}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));
        as(editor, post("/api/v1/admin/goals"), "{\"code\": \"" + goal + "\", \"label\": \"Дубль\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOAL_EXISTS"));
        mvc.perform(get("/api/v1/goals")).andExpect(jsonPath("$[*].code", hasItem(goal)));

        as(editor, put("/api/v1/admin/goals/" + goal), "{\"label\": \"Совместные путешествия\", \"sortOrder\": 71}")
                .andExpect(jsonPath("$.label").value("Совместные путешествия"));
        as(editor, put("/api/v1/admin/goals/NO_SUCH_GOAL"), "{\"label\": \"x\"}").andExpect(status().isNotFound());

        String user = login(newPhone());
        putProfile(user, "{\"goalCodes\": [\"" + goal + "\", \"DATING\"]}").andExpect(status().isOk());
        as(editor, delete("/api/v1/admin/goals/" + goal), null).andExpect(jsonPath("$.result").value("DEACTIVATED"));
        mvc.perform(get("/api/v1/goals")).andExpect(jsonPath("$[*].code", not(hasItem(goal))));
        as(user, get("/api/v1/me/profile"), null).andExpect(jsonPath("$.goals[*].code", contains("DATING")));

        as(editor, post("/api/v1/admin/goals"), "{\"code\": \"" + unused + "\", \"label\": \"Временная\"}")
                .andExpect(status().isCreated());
        as(editor, delete("/api/v1/admin/goals/" + unused), null).andExpect(jsonPath("$.result").value("DELETED"));
        as(editor, get("/api/v1/admin/goals"), null)
                .andExpect(jsonPath("$[*].code", not(hasItem(unused))))
                .andExpect(jsonPath("$[?(@.code == '" + goal + "')].active", contains(false)));

        admin(get("/api/v1/admin/audit").queryParam("entityType", "GOAL").queryParam("entityId", goal), null)
                .andExpect(jsonPath("$[*].action", contains("DEACTIVATE", "UPDATE", "CREATE")));
        admin(get("/api/v1/admin/audit").queryParam("entityType", "GOAL").queryParam("limit", "1"), null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].entityId").value(unused));

        // The seeded goals stay intact for the other tests.
        mvc.perform(get("/api/v1/goals"))
                .andExpect(jsonPath("$[*].code", hasItems("SERIOUS_RELATIONSHIP", "DATING", "NOT_SURE")));
    }
}
