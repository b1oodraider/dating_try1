package com.dating;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Liking everybody in a row gets likes restricted and those likes removed from the receivers' "who liked me". */
class LikeSpamIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void likingEverybodyFastIsRestrictedAndTheLikesVanish() throws Exception {
        String city = uniqueCity();
        User spammer = user(city, "Спамер");
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            people.add(user(city, "Человек " + i));
        }
        // One of them liked the spammer first: that like becomes a match and must survive.
        User mutual = people.get(0);
        like(mutual, spammer).andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            like(spammer, people.get(i)).andExpect(status().isOk());
        }
        like(spammer, people.get(10)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.details.kind").value("LIKES"));
        as(spammer.token(), get("/api/v1/me/restrictions"), null)
                .andExpect(jsonPath("$[0].reason").value("LIKE_SPAM"));

        for (int i = 1; i < 10; i++) {
            receivedCount(people.get(i), 0);
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM matches WHERE closed_at IS NULL
                  AND account_low = LEAST(?::uuid, ?::uuid) AND account_high = GREATEST(?::uuid, ?::uuid)""",
                Integer.class, spammer.accountId(), mutual.accountId(), spammer.accountId(), mutual.accountId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM profile_reactions WHERE actor_account_id = ? AND type IN ('LIKE', 'SUPERLIKE')""",
                Integer.class, spammer.accountId()), "only the like behind the match is kept");

        // Passing still works; a repeat after the restriction is lifted escalates (1 h → 2 h).
        as(spammer.token(), post("/api/v1/discovery/profiles/" + people.get(10).profileId() + "/pass"), null)
                .andExpect(status().isOk());
        String restrictionId = jdbc.queryForObject("SELECT id::text FROM account_restrictions WHERE account_id = ?",
                String.class, spammer.accountId());
        admin(post("/api/v1/admin/restrictions/" + restrictionId + "/lift"), null).andExpect(status().isOk());
        like(spammer, people.get(1)).andExpect(status().isOk()); // the window is still full of blind likes
        like(spammer, people.get(2)).andExpect(status().isForbidden());
        assertEquals(List.of(1L, 2L), jdbc.queryForList("""
                SELECT round(extract(epoch FROM ends_at - starts_at) / 3600)::bigint FROM account_restrictions
                WHERE account_id = ? AND reason = 'LIKE_SPAM' ORDER BY starts_at""", Long.class, spammer.accountId()));
    }

    @Test
    void ordinaryLikingIsNotPunished() throws Exception {
        String city = uniqueCity();
        User picky = user(city, "Разборчивая");
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            people.add(user(city, "Кандидат " + i));
        }
        for (int i = 0; i < 10; i++) {
            String action = i % 2 == 0 ? "/like" : "/pass";
            as(picky.token(), post("/api/v1/discovery/profiles/" + people.get(i).profileId() + action), null)
                    .andExpect(status().isOk());
        }
        as(picky.token(), get("/api/v1/me/restrictions"), null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clientViewsAreStoredAndValidated() throws Exception {
        String city = uniqueCity();
        User viewer = user(city, "Зритель");
        User seen = user(city, "Видимая");
        as(viewer.token(), post("/api/v1/discovery/views"), """
                {"views": [{"profileId": "%s", "dwellMs": 2500, "openedDetails": true},
                           {"profileId": "%s", "dwellMs": 10}]}""".formatted(seen.profileId(), UUID.randomUUID()))
                .andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM profile_views WHERE viewer_account_id = ?",
                Integer.class, viewer.accountId()), "unknown profiles are skipped");
        as(viewer.token(), post("/api/v1/discovery/views"), "{\"views\": []}").andExpect(status().isBadRequest());
        as(viewer.token(), post("/api/v1/discovery/views"),
                "{\"views\": [{\"profileId\": \"" + seen.profileId() + "\", \"dwellMs\": -1}]}").andExpect(status().isBadRequest());
    }

    private User user(String city, String name) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return new User(token, UUID.fromString(accountId(token)), json(get("/api/v1/me/profile"), token, "$.id"));
    }

    private ResultActions like(User from, User to) throws Exception {
        return as(from.token(), post("/api/v1/discovery/profiles/" + to.profileId() + "/like"), null);
    }

    private void receivedCount(User user, int expected) throws Exception {
        as(user.token(), get("/api/v1/me/likes/received/count"), null).andExpect(jsonPath("$.count").value(expected));
    }
}
