package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Date plan in a chat (docs/date-plan.md) and the places to pick from. */
class DatePlanIntegrationTest extends AdminTestSupport {

    private static final String WHEN = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).toString();

    private static String item(String place, String activity) {
        return "{\"placeName\": \"" + place + "\", \"latitude\": 55.75, \"longitude\": 37.61, \"startsAt\": \"" + WHEN
                + "\", \"activity\": \"" + activity + "\", \"comment\": \"У входа\"}";
    }

    @Test
    void premiumProposesTheOtherSideAnswersEditsAreCounterProposalsAndAllIsInTheChat() throws Exception {
        String city = uniqueCity();
        String anna = login(newPhone());
        publishWith(anna, "Анна", city, List.of());
        String boris = login(newPhone());
        publishWith(boris, "Борис", city, List.of());
        String matchId = match(anna, boris);
        String plan = "/api/v1/matches/" + matchId + "/date-plan";

        as(anna, get(plan), null).andExpect(status().isOk()).andExpect(jsonPath("$.plan").value(nullValue()));
        as(anna, post(plan), "{\"items\": [" + item("Кофейня", "COFFEE") + "]}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PREMIUM_REQUIRED"));

        grantPremium(anna, 30);
        as(anna, post(plan), "{\"items\": []}").andExpect(status().isBadRequest());
        as(anna, post(plan), "{\"items\": [{\"placeName\": \"Прошлое\", \"latitude\": 55.7, \"longitude\": 37.6,"
                + " \"startsAt\": \"2020-01-01T10:00:00Z\", \"activity\": \"WALK\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['items[0].startsAt']").exists());
        MvcResult proposed = as(anna, post(plan), "{\"items\": [" + item("Кофейня", "COFFEE") + ", "
                + item("Парк", "WALK") + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.canRespond").value(false))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn();
        String planId = JsonPath.read(body(proposed), "$.id");
        as(anna, post(plan), "{\"items\": [" + item("Ещё", "FOOD") + "]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_ALREADY_OPEN"));

        // The other side sees it in the chat and may answer; the proposer may not answer their own plan.
        as(boris, get("/api/v1/matches/" + matchId + "/messages"), null)
                .andExpect(jsonPath("$.items[0].type").value("DATE_PLAN"))
                .andExpect(jsonPath("$.items[0].datePlanId").value(planId))
                .andExpect(jsonPath("$.items[0].text", startsWith("Предлагаю встречу: Кофейня")));
        as(boris, get(plan), null).andExpect(jsonPath("$.plan.canRespond").value(true))
                .andExpect(jsonPath("$.plan.canEdit").value(false));
        as(anna, post("/api/v1/date-plans/" + planId + "/accept"), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_YOURS_TO_ANSWER"));

        // Editing needs premium; a premium edit is a counter proposal the other side answers.
        as(boris, put("/api/v1/date-plans/" + planId), "{\"version\": 1, \"items\": [" + item("Кино", "CINEMA") + "]}")
                .andExpect(status().isForbidden());
        grantPremium(boris, 30);
        as(boris, put("/api/v1/date-plans/" + planId), "{\"version\": 7, \"items\": [" + item("Кино", "CINEMA") + "]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_CHANGED"));
        as(boris, put("/api/v1/date-plans/" + planId), "{\"version\": 1, \"items\": [" + item("Кино", "CINEMA") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lastEditMine").value(true))
                .andExpect(jsonPath("$.items[0].placeName").value("Кино"));
        as(anna, post("/api/v1/date-plans/" + planId + "/accept"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        as(anna, get("/api/v1/date-plans/" + planId + "/history"), null)
                .andExpect(jsonPath("$[*].action", contains("PROPOSE", "EDIT", "ACCEPT")))
                .andExpect(jsonPath("$[1].items[0].placeName").value("Кино"));
        as(boris, post("/api/v1/date-plans/" + planId + "/cancel"), null)
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        as(boris, post("/api/v1/date-plans/" + planId + "/cancel"), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_CLOSED"));
        as(anna, get(plan), null).andExpect(jsonPath("$.plan").value(nullValue()));

        // Strangers see nothing.
        String stranger = login(newPhone());
        as(stranger, get("/api/v1/date-plans/" + planId), null).andExpect(status().isNotFound());
    }

    @Test
    void venuesAreCuratedByStaffAndSponsoredOnesCarryTheAdLabel() throws Exception {
        String editor = editorToken();
        String user = login(newPhone());
        as(user, post("/api/v1/admin/venues"), "{\"name\": \"X\", \"latitude\": 1, \"longitude\": 1, \"category\": \"COFFEE\"}")
                .andExpect(status().isForbidden());
        as(editor, post("/api/v1/admin/venues"), "{\"name\": \"Реклама без рекламодателя\", \"latitude\": 10.5,"
                + " \"longitude\": 10.5, \"category\": \"FOOD\", \"sponsored\": true}")
                .andExpect(status().isBadRequest());
        as(editor, post("/api/v1/admin/venues"), "{\"name\": \"Кафе «Спонсор»\", \"latitude\": 10.501, \"longitude\": 10.502,"
                + " \"category\": \"FOOD\", \"sponsored\": true, \"advertiser\": \"ООО «Кафе»\", \"erid\": \"2VtzqX\"}")
                .andExpect(status().isCreated());
        as(editor, post("/api/v1/admin/venues"), "{\"name\": \"Сквер\", \"latitude\": 10.5, \"longitude\": 10.5,"
                + " \"category\": \"WALK\"}").andExpect(status().isCreated());

        as(user, get("/api/v1/venues").param("latitude", "10.5").param("longitude", "10.5"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Кафе «Спонсор»"))
                .andExpect(jsonPath("$[0].ad.label").value("Реклама"))
                .andExpect(jsonPath("$[0].ad.advertiser").value("ООО «Кафе»"))
                .andExpect(jsonPath("$[0].ad.erid").value("2VtzqX"))
                .andExpect(jsonPath("$[1].ad").value(nullValue()));
        as(user, get("/api/v1/venues").param("latitude", "-40").param("longitude", "-40"), null)
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private String match(String a, String b) throws Exception {
        String bProfile = json(get("/api/v1/me/profile"), b, "$.id");
        String aProfile = json(get("/api/v1/me/profile"), a, "$.id");
        as(a, post("/api/v1/discovery/profiles/" + bProfile + "/like"), null).andExpect(status().isOk());
        MvcResult result = as(b, post("/api/v1/discovery/profiles/" + aProfile + "/like"), null)
                .andExpect(jsonPath("$.matched").value(true)).andReturn();
        return JsonPath.read(body(result), "$.matchId");
    }
}
