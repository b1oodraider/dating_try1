package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Behavioral metrics: ghosting, one-word answers, initiative, selectivity — computed from what people did. */
class BehaviorMetricsIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void metricsDescribeHowSomebodyBehaves() throws Exception {
        String city = uniqueCity();
        User anna = user(city, "Анна");
        User boris = user(city, "Борис");
        User vadim = user(city, "Вадим");
        User gleb = user(city, "Глеб");
        User denis = user(city, "Денис");

        discovery(anna.token(), city, null, null, 50).andExpect(status().isOk());

        String withBoris = match(boris, anna);
        String withVadim = match(vadim, anna);
        String withGleb = match(gleb, anna);
        reaction(anna, denis, "pass");

        send(boris, withBoris, "Привет, как проходит неделя?");
        send(anna, withBoris, "норм");                       // answers, in one word
        send(vadim, withVadim, "Привет!");                    // anna never answers: ghosting
        send(anna, withGleb, "Добрый вечер, видела ваши фото"); // anna writes first

        as(anna.token(), get("/api/v1/admin/accounts/" + anna.accountId() + "/behavior"), null)
                .andExpect(status().isForbidden());
        String moderator = moderator();
        as(moderator, get("/api/v1/admin/accounts/" + anna.accountId() + "/behavior"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.likesGiven30d").value(3))
                .andExpect(jsonPath("$.metrics.passesGiven30d").value(1))
                .andExpect(jsonPath("$.metrics.likeRatio").value(0.75))
                .andExpect(jsonPath("$.metrics.profilesSeen30d").value(greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.metrics.matches90d").value(3))
                .andExpect(jsonPath("$.metrics.dialogs90d").value(3))
                .andExpect(jsonPath("$.metrics.silentMatches90d").value(0))
                .andExpect(jsonPath("$.metrics.responseRate").value(0.5))
                .andExpect(jsonPath("$.metrics.ghostRate").value(0.5))
                .andExpect(jsonPath("$.metrics.initiationRate").value(0.333))
                .andExpect(jsonPath("$.metrics.oneWordShare").value(0.5))
                .andExpect(jsonPath("$.metrics.messagesSent90d").value(2))
                .andExpect(jsonPath("$.metrics.likesReceived30d").value(3))
                .andExpect(jsonPath("$.metrics.activeDays30d").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM behavior_metrics WHERE account_id = ?", Integer.class,
                anna.accountId()));

        // Nobody else's metrics leak the text: only numbers are stored.
        String stored = jdbc.queryForObject("SELECT metrics::text FROM behavior_metrics WHERE account_id = ?",
                String.class, anna.accountId());
        assertEquals(-1, stored.indexOf("норм"));

        as(moderator, get("/api/v1/admin/accounts/" + UUID.randomUUID() + "/behavior"), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingTheAccountDeletesItsBehavior() throws Exception {
        String city = uniqueCity();
        User gone = user(city, "Уходящий");
        User other = user(city, "Остающийся");
        discovery(gone.token(), city, null, null, 50);
        reaction(gone, other, "like");
        as(gone.token(), post("/api/v1/discovery/views"),
                "{\"views\": [{\"profileId\": \"" + other.profileId() + "\", \"dwellMs\": 1200}]}")
                .andExpect(status().isNoContent());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                .header("Authorization", bearer(gone.token()))).andExpect(status().isNoContent());
        for (String sql : List.of(
                "SELECT count(*) FROM reaction_events WHERE actor_account_id = ?",
                "SELECT count(*) FROM profile_views WHERE viewer_account_id = ?",
                "SELECT count(*) FROM feed_impressions WHERE viewer_account_id = ?",
                "SELECT count(*) FROM account_activity_days WHERE account_id = ?")) {
            assertEquals(0, jdbc.queryForObject(sql, Integer.class, gone.accountId()), sql);
        }
    }

    // ------------------------------------------------------------------ helpers

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

    private ResultActions reaction(User from, User to, String type) throws Exception {
        return as(from.token(), post("/api/v1/discovery/profiles/" + to.profileId() + "/" + type), null)
                .andExpect(status().isOk());
    }

    private String match(User a, User b) throws Exception {
        reaction(a, b, "like");
        MvcResult result = reaction(b, a, "like").andReturn();
        return JsonPath.read(body(result), "$.matchId");
    }

    private void send(User from, String matchId, String text) throws Exception {
        as(from.token(), post("/api/v1/matches/" + matchId + "/messages"),
                "{\"clientMessageId\": \"" + UUID.randomUUID() + "\", \"type\": \"TEXT\", \"text\": \"" + text + "\"}")
                .andExpect(status().isOk());
    }
}
