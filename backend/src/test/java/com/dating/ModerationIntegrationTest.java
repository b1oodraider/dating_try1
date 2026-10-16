package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModerationIntegrationTest extends AdminTestSupport {

    @Test
    void moderatorAcceptsReclassifiesAndRejectsReports() throws Exception {
        String reported = login(newPhone());
        publishWith(reported, "Нарушитель", uniqueCity(), List.of());
        String profileId = json(get("/api/v1/me/profile"), reported, "$.id");
        String reporter = login(newPhone());

        String spam = fileReport(reporter, profileId, "SPAM");
        String fake = fileReport(reporter, profileId, "FAKE_PROFILE");
        String other = fileReport(reporter, profileId, "OTHER");

        // Ordinary users and content editors have no access.
        as(reporter, get("/api/v1/admin/reports"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));
        as(editorToken(), get("/api/v1/admin/reports/" + spam), null).andExpect(status().isForbidden());

        String moderator = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(moderator) + "/roles/MODERATOR"), null)
                .andExpect(status().isOk());

        as(moderator, get("/api/v1/admin/reports?limit=100"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(spam)));
        as(moderator, get("/api/v1/admin/reports/" + spam), null)
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.reportedProfileId").value(profileId))
                .andExpect(jsonPath("$.reportedDisplayName").value("Нарушитель"))
                .andExpect(jsonPath("$.reportedConfirmedViolations").value(0));

        // Accept: the confirmed violation is the reported one; a reason is not allowed here.
        decide(moderator, spam, "{\"decision\": \"ACCEPTED\", \"reason\": \"SCAM\"}").andExpect(status().isBadRequest());
        decide(moderator, spam, "{\"decision\": \"ACCEPTED\", \"note\": \" реклама \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.finalReason").value("SPAM"))
                .andExpect(jsonPath("$.moderatorNote").value("реклама"))
                .andExpect(jsonPath("$.moderatorAccountId").value(accountId(moderator)));
        decide(moderator, spam, "{\"decision\": \"REJECTED\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_ALREADY_DECIDED"));

        // Reclassify: needs a reason different from the reported one.
        decide(moderator, fake, "{\"decision\": \"RECLASSIFIED\"}").andExpect(status().isBadRequest());
        decide(moderator, fake, "{\"decision\": \"RECLASSIFIED\", \"reason\": \"FAKE_PROFILE\"}").andExpect(status().isBadRequest());
        decide(moderator, fake, "{\"decision\": \"RECLASSIFIED\", \"reason\": \"SCAM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("FAKE_PROFILE"))
                .andExpect(jsonPath("$.finalReason").value("SCAM"))
                .andExpect(jsonPath("$.reportedConfirmedViolations").value(2));

        // Reject: no violation.
        decide(moderator, other, "{\"decision\": \"REJECTED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.finalReason").doesNotExist())
                .andExpect(jsonPath("$.reportedConfirmedViolations").value(2));

        // Decided reports leave the open queue and show up by status; every decision is audited.
        as(moderator, get("/api/v1/admin/reports?limit=100"), null)
                .andExpect(jsonPath("$.items[*].id", not(hasItem(spam))));
        as(moderator, get("/api/v1/admin/reports?status=dismissed&limit=100"), null)
                .andExpect(jsonPath("$.items[*].id", hasItem(other)));
        as(moderator, get("/api/v1/admin/reports?status=WHATEVER"), null).andExpect(status().isBadRequest());
        admin(get("/api/v1/admin/audit?entityType=REPORT&entityId=" + fake), null)
                .andExpect(jsonPath("$[0].action").value("RECLASSIFIED"));
        as(moderator, get("/api/v1/admin/reports/00000000-0000-0000-0000-000000000000"), null)
                .andExpect(status().isNotFound());
    }

    private String fileReport(String token, String profileId, String reason) throws Exception {
        MvcResult result = as(token, post("/api/v1/reports"),
                "{\"profileId\": \"" + profileId + "\", \"reason\": \"" + reason + "\"}")
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(body(result), "$.id");
    }

    private ResultActions decide(String token, String reportId, String body)
            throws Exception {
        return as(token, post("/api/v1/admin/reports/" + reportId + "/decision"), body);
    }
}
