package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report decisions turn into sanctions; a ban wipes the account's likes from everybody's "who liked me". */
class SanctionsIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void spamEscalatesAndBlocksLikesAndMessages() throws Exception {
        String city = uniqueCity();
        User spammer = user(city, "Спамер");
        User other = user(city, "Ольга");
        User reporter = user(city, "Рита");
        String moderator = moderator();
        String matchId = match(spammer, other);

        decide(moderator, report(reporter, spammer, "SPAM"), "{\"decision\": \"ACCEPTED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.sanctioned").value(true));

        like(spammer, reporter).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.details.kind").value("LIKES"))
                .andExpect(jsonPath("$.details.until").exists());
        as(spammer.token(), post("/api/v1/matches/" + matchId + "/messages"),
                "{\"clientMessageId\": \"x\", \"type\": \"TEXT\", \"text\": \"купи\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.details.kind").value("MESSAGES"));
        as(spammer.token(), get("/api/v1/me/restrictions"), null)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].reason", hasItem("SPAM")));

        // The second sanctioned SPAM doubles the duration; a duplicate report can be confirmed without a sanction.
        decide(moderator, report(reporter, spammer, "SPAM"), "{\"decision\": \"ACCEPTED\"}").andExpect(status().isOk());
        decide(moderator, report(other, spammer, "SPAM"), "{\"decision\": \"ACCEPTED\", \"sanction\": false}")
                .andExpect(jsonPath("$.sanctioned").value(false));
        List<Long> hours = jdbc.queryForList("""
                SELECT round(extract(epoch FROM ends_at - starts_at) / 3600)::bigint FROM account_restrictions
                WHERE account_id = ? AND kind = 'LIKES' ORDER BY starts_at""", Long.class, spammer.accountId());
        assertEquals(List.of(1L, 2L), hours);
    }

    @Test
    void banRemovesAllLikesFromEverybodysWhoLikedMeAndClosesMatches() throws Exception {
        String city = uniqueCity();
        User scammer = user(city, "Скам");
        User honest = user(city, "Честный");
        User victim = user(city, "Вика");
        User friend = user(city, "Фёдор");
        like(scammer, victim).andExpect(status().isOk());
        like(honest, victim).andExpect(status().isOk());
        String matchId = match(scammer, friend);
        receivedCount(victim, 2);

        String moderator = moderator();
        decide(moderator, report(victim, scammer, "SCAM"), "{\"decision\": \"ACCEPTED\"}").andExpect(status().isOk());

        receivedCount(victim, 1);
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM profile_reactions WHERE actor_account_id = ? AND type IN ('LIKE', 'SUPERLIKE')""",
                Integer.class, scammer.accountId()));
        assertEquals("MODERATION", jdbc.queryForObject("SELECT close_reason FROM matches WHERE id = ?::uuid",
                String.class, matchId));
        as(scammer.token(), get("/api/v1/me"), null).andExpect(status().isUnauthorized());
        assertEquals("SUSPENDED", jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class,
                scammer.accountId()));
        discovery(friend.token(), city, null, null, 50).andExpect(jsonPath("$.items[*].id", not(hasItem(scammer.profileId()))));

        // Lifting the ban (admin only) restores the account, but not the removed likes.
        String restrictionId = jdbc.queryForObject("""
                SELECT id::text FROM account_restrictions WHERE account_id = ? AND kind = 'SUSPENDED'""",
                String.class, scammer.accountId());
        as(moderator, post("/api/v1/admin/restrictions/" + restrictionId + "/lift"), "{}")
                .andExpect(status().isForbidden());
        admin(post("/api/v1/admin/restrictions/" + restrictionId + "/lift"), "{\"note\": \"ошибка\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.liftNote").value("ошибка"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class,
                scammer.accountId()));
        receivedCount(victim, 1);
    }

    @Test
    void underageReportHidesProfileUntilDecided() throws Exception {
        String city = uniqueCity();
        User suspect = user(city, "Подозрение");
        User viewer = user(city, "Зритель");
        User liked = user(city, "Лайкнутая");
        like(suspect, liked).andExpect(status().isOk());

        String reportId = report(viewer, suspect, "UNDERAGE");
        discovery(viewer.token(), city, null, null, 50).andExpect(jsonPath("$.items[*].id", not(hasItem(suspect.profileId()))));
        receivedCount(liked, 0);

        decide(moderator(), reportId, "{\"decision\": \"REJECTED\"}").andExpect(status().isOk());
        discovery(viewer.token(), city, null, null, 50).andExpect(jsonPath("$.items[*].id", hasItem(suspect.profileId())));
        receivedCount(liked, 1);
    }

    @Test
    void inappropriateContentIsRemovedAndThirdStrikeHides() throws Exception {
        String city = uniqueCity();
        User offender = user(city, "Нарушитель");
        putProfile(offender.token(), "{\"aboutText\": \"очень плохой текст здесь\"}").andExpect(status().isOk());
        String extraPhoto = uploadPng(offender.token());
        User reporter = user(city, "Репортёр");
        String moderator = moderator();

        decide(moderator, report(reporter, offender, "INAPPROPRIATE_CONTENT"),
                "{\"decision\": \"REJECTED\", \"clearAboutText\": true}").andExpect(status().isBadRequest());
        decide(moderator, report(reporter, offender, "INAPPROPRIATE_CONTENT"),
                "{\"decision\": \"ACCEPTED\", \"removePhotoIds\": [\"" + extraPhoto + "\"], \"clearAboutText\": true}")
                .andExpect(status().isOk());
        as(offender.token(), get("/api/v1/me/profile"), null)
                .andExpect(jsonPath("$.aboutText").doesNotExist())
                .andExpect(jsonPath("$.photos", hasSize(1)));
        as(offender.token(), get("/api/v1/me/restrictions"), null).andExpect(jsonPath("$", hasSize(0)));

        decide(moderator, report(reporter, offender, "INAPPROPRIATE_CONTENT"), "{\"decision\": \"ACCEPTED\"}");
        decide(moderator, report(reporter, offender, "INAPPROPRIATE_CONTENT"), "{\"decision\": \"ACCEPTED\"}");
        as(offender.token(), get("/api/v1/me/restrictions"), null)
                .andExpect(jsonPath("$[0].kind").value("HIDDEN"));
        discovery(reporter.token(), city, null, null, 50).andExpect(jsonPath("$.items[*].id", not(hasItem(offender.profileId()))));
    }

    @Test
    void manualRestrictionsAndPolicyNeedRoles() throws Exception {
        User target = user(uniqueCity(), "Цель");
        String moderator = moderator();
        String id = json(as(moderator, post("/api/v1/admin/accounts/" + target.accountId() + "/restrictions"),
                "{\"kind\": \"LIKES\", \"hours\": 3, \"note\": \"вручную\"}"), "$.id", 201);
        as(moderator, get("/api/v1/admin/accounts/" + target.accountId() + "/restrictions"), null)
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].reason").value("MANUAL"))
                .andExpect(jsonPath("$[0].source").value("MODERATOR"));
        as(target.token(), post("/api/v1/admin/accounts/" + target.accountId() + "/restrictions"),
                "{\"kind\": \"LIKES\"}").andExpect(status().isForbidden());

        String policy = "{\"windowDays\": 60, \"holdOnReports\": null, \"steps\": [{\"restrictions\": [{\"kind\": \"MESSAGES\", \"hours\": 24}]}]}";
        as(moderator, put("/api/v1/admin/sanction-policy/OTHER"), policy).andExpect(status().isForbidden());
        admin(put("/api/v1/admin/sanction-policy/other"), policy)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(60))
                .andExpect(jsonPath("$.steps[0].restrictions[0].kind").value("MESSAGES"));
        admin(put("/api/v1/admin/sanction-policy/NOPE"), policy).andExpect(status().isBadRequest());
        admin(put("/api/v1/admin/sanction-policy/OTHER"), "{\"windowDays\": 60, \"steps\": []}").andExpect(status().isBadRequest());
        admin(put("/api/v1/admin/sanction-policy/OTHER"), "{\"windowDays\": 365, \"steps\": [{\"restrictions\": []}]}")
                .andExpect(status().isOk());
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

    private ResultActions like(User from, User to) throws Exception {
        return as(from.token(), post("/api/v1/discovery/profiles/" + to.profileId() + "/like"), null);
    }

    private String match(User a, User b) throws Exception {
        like(a, b).andExpect(status().isOk());
        return json(like(b, a), "$.matchId", 200);
    }

    private String report(User from, User about, String reason) throws Exception {
        return json(as(from.token(), post("/api/v1/reports"),
                "{\"profileId\": \"" + about.profileId() + "\", \"reason\": \"" + reason + "\"}"), "$.id", 201);
    }

    private ResultActions decide(String moderator, String reportId, String body) throws Exception {
        return as(moderator, post("/api/v1/admin/reports/" + reportId + "/decision"), body);
    }

    private void receivedCount(User user, int expected) throws Exception {
        as(user.token(), get("/api/v1/me/likes/received/count"), null).andExpect(jsonPath("$.count").value(expected));
    }

    private static String json(ResultActions actions, String path, int status) throws Exception {
        MvcResult result = actions.andExpect(status().is(status)).andReturn();
        return JsonPath.read(body(result), path).toString();
    }
}
