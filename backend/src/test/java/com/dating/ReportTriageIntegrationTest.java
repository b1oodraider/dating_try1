package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report triage: urgent reports first, with the reasons; complaints about silence never reach a moderator. */
class ReportTriageIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void urgentReportsComeFirstWithTheirReasons() throws Exception {
        String city = uniqueCity();
        User spammer = user(city, "Спамер");
        User minor = user(city, "Подросток");
        User threatener = user(city, "Угрожающий");
        User a = user(city, "Аня");
        User b = user(city, "Боря");

        String spam = report(a, spammer, "SPAM", null).andExpect(jsonPath("$.status").value("NEW")).andReturn()
                .getResponse().getContentAsString();
        String spamId = JsonPath.read(spam, "$.id");
        String minorId = id(report(a, minor, "UNDERAGE", null));
        String threatId = id(report(a, threatener, "HARASSMENT", "Написал: знаю, где ты живёшь"));
        id(report(b, threatener, "HARASSMENT", null));

        String moderator = moderator();
        int spamPriority = priority(moderator, spamId);
        int minorPriority = priority(moderator, minorId);
        int threatPriority = priority(moderator, threatId);
        assertTrue(minorPriority > spamPriority, minorPriority + " > " + spamPriority);
        assertTrue(threatPriority > minorPriority, "a threat beats every category: " + threatPriority);
        as(moderator, get("/api/v1/admin/reports/" + threatId), null)
                .andExpect(jsonPath("$.priorityReasons", hasItem("похоже на угрозу (+60)")))
                .andExpect(jsonPath("$.priorityReasons", hasItem("открытые жалобы от 2 человек (+10)")));

        // The default queue is sorted by priority; "oldest" keeps arrival order.
        List<Integer> priorities = JsonPath.read(body(as(moderator, get("/api/v1/admin/reports?limit=100"), null)
                .andExpect(status().isOk()).andReturn()), "$.items[*].priority");
        for (int i = 1; i < priorities.size(); i++) {
            assertTrue(priorities.get(i - 1) >= priorities.get(i), "sorted by priority: " + priorities);
        }
        as(moderator, get("/api/v1/admin/reports?sort=oldest&limit=5"), null).andExpect(status().isOk());
        as(moderator, get("/api/v1/admin/reports?sort=random"), null).andExpect(status().isBadRequest());

        // Silence is not a violation, so it cannot be the confirmed reason either.
        as(moderator, post("/api/v1/admin/reports/" + spamId + "/decision"),
                "{\"decision\": \"RECLASSIFIED\", \"reason\": \"NOT_RESPONDING\"}").andExpect(status().isBadRequest());
    }

    @Test
    void complaintsAboutSilenceAreClosedAutomatically() throws Exception {
        String city = uniqueCity();
        User silent = user(city, "Молчун");
        User reporter = user(city, "Ждущая");
        String matchId = match(reporter, silent);

        report(reporter, silent, "NOT_RESPONDING", null)
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.note").exists());
        String other = id(report(reporter, silent, "OTHER", "Он мне не отвечает уже неделю"));

        String moderator = moderator();
        as(moderator, get("/api/v1/admin/reports/" + other), null)
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.autoDismissed").value(true))
                .andExpect(jsonPath("$.moderatorAccountId").doesNotExist());
        as(moderator, get("/api/v1/admin/reports?limit=100"), null)
                .andExpect(jsonPath("$.items[*].id", not(hasItem(other))));
        as(moderator, get("/api/v1/admin/accounts/" + silent.accountId() + "/risk"), null)
                .andExpect(jsonPath("$.signals[*].code", not(hasItem("REPORTED_BY_MANY"))));

        // Once the person did write, "he ignores me" may hide something else: a human looks at it.
        as(silent.token(), post("/api/v1/matches/" + matchId + "/messages"),
                "{\"clientMessageId\": \"m1\", \"type\": \"TEXT\", \"text\": \"Привет\"}").andExpect(status().isOk());
        report(reporter, silent, "OTHER", "Теперь игнорирует и пишет странное").andExpect(jsonPath("$.status").value("NEW"));
    }

    // ------------------------------------------------------------------ helpers

    private int priority(String moderator, String reportId) throws Exception {
        MvcResult result = as(moderator, get("/api/v1/admin/reports/" + reportId), null)
                .andExpect(jsonPath("$.priority").value(greaterThan(0))).andReturn();
        return JsonPath.read(body(result), "$.priority");
    }

    private ResultActions report(User from, User about, String reason, String comment) throws Exception {
        return as(from.token(), post("/api/v1/reports"), "{\"profileId\": \"" + about.profileId() + "\", \"reason\": \""
                + reason + "\"" + (comment == null ? "" : ", \"comment\": \"" + comment + "\"") + "}")
                .andExpect(status().isCreated());
    }

    private static String id(ResultActions actions) throws Exception {
        return JsonPath.read(body(actions.andReturn()), "$.id");
    }

    private String match(User a, User b) throws Exception {
        as(a.token(), post("/api/v1/discovery/profiles/" + b.profileId() + "/like"), null).andExpect(status().isOk());
        return JsonPath.read(body(as(b.token(), post("/api/v1/discovery/profiles/" + a.profileId() + "/like"), null)
                .andExpect(status().isOk()).andReturn()), "$.matchId");
    }

    private User user(String city, String name) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return new User(token, UUID.fromString(accountId(token)), json(get("/api/v1/me/profile"), token, "$.id"));
    }

    private String moderator() throws Exception {
        String token = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(token) + "/roles/MODERATOR"), null).andExpect(status().isOk());
        return token;
    }
}
