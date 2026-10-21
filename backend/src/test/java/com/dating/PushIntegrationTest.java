package com.dating;

import com.dating.support.PushStub;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pushes for likes, matches and messages through both providers (PushStub fakes RuStore and FCM). */
class PushIntegrationTest extends IntegrationTestBase {

    private static final long WAIT = 5_000;

    record User(String token, UUID accountId, UUID profileId) {
    }

    @Test
    void likesMatchesAndMessagesArePushedThroughBothProviders() throws Exception {
        String city = uniqueCity();
        User anna = user(city, "Анна");
        User boris = user(city, "Борис");
        String annaToken = "rustore-" + UUID.randomUUID();
        String borisToken = "fcm-" + UUID.randomUUID();
        register(anna, "RUSTORE", annaToken).andExpect(status().isNoContent());
        register(boris, "FCM", borisToken).andExpect(status().isNoContent());

        like(anna, boris);
        PushStub.Push like = PushStub.next(borisToken, WAIT);
        assertNotNull(like, "like is pushed to the liked person");
        assertEquals("FCM", like.provider());
        assertEquals("Bearer fcm-access-token", like.authorization(), "FCM uses the OAuth token from the service account");
        assertTrue(like.body().contains("\"type\":\"like_received\""), like.body());
        assertFalse(like.body().contains("Анна"), "no names on the lock screen");

        MvcResult matched = like(boris, anna);
        String matchId = JsonPath.read(matched.getResponse().getContentAsString(), "$.matchId");
        PushStub.Push annaMatch = PushStub.next(annaToken, WAIT);
        assertNotNull(annaMatch);
        assertEquals("RUSTORE", annaMatch.provider());
        assertEquals("Bearer " + PushStub.RUSTORE_SERVICE_TOKEN, annaMatch.authorization());
        assertTrue(annaMatch.body().contains("\"matchId\":\"" + matchId + "\""), annaMatch.body());
        assertNotNull(PushStub.next(borisToken, WAIT), "both sides get the match");

        mvc.perform(post("/api/v1/matches/" + matchId + "/messages")
                        .header("Authorization", bearer(anna.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\": \"m1\", \"type\": \"TEXT\", \"text\": \"Секретный текст\"}"))
                .andExpect(status().isOk());
        PushStub.Push message = PushStub.next(borisToken, WAIT);
        assertNotNull(message, "the recipient gets a push");
        assertTrue(message.body().contains("\"type\":\"message\""), message.body());
        assertFalse(message.body().contains("Секретный"), "message text never goes to the push provider");
        assertNull(PushStub.next(annaToken, 500), "the sender gets nothing");
    }

    @Test
    void deadTokensAreForgottenAndLogoutStopsPushes() throws Exception {
        String city = uniqueCity();
        User anna = user(city, "Анна");
        User boris = user(city, "Борис");
        User vera = user(city, "Вера");

        String dead = "fcm-dead-" + UUID.randomUUID();
        PushStub.DEAD_TOKENS.add(dead);
        register(boris, "FCM", dead).andExpect(status().isNoContent());
        like(anna, boris);
        waitUntilDeviceGone(dead);

        // A refreshed token replaces the previous one of the same session.
        String old = "rustore-old-" + UUID.randomUUID();
        String fresh = "rustore-new-" + UUID.randomUUID();
        register(vera, "RUSTORE", old).andExpect(status().isNoContent());
        register(vera, "RUSTORE", fresh).andExpect(status().isNoContent());
        assertEquals(List.of(fresh), jdbc.queryForList("SELECT token FROM push_devices WHERE account_id = ?",
                String.class, vera.accountId()));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(vera.token()))).andExpect(status().isNoContent());
        like(anna, vera);
        assertNull(PushStub.next(fresh, 1_000), "a logged-out device gets nothing");
    }

    @Test
    void registrationIsValidatedAndCanBeUndone() throws Exception {
        User anna = user(uniqueCity(), "Анна");
        register(anna, "APNS", "x").andExpect(status().isBadRequest());
        register(anna, "FCM", " ").andExpect(status().isBadRequest());
        register(anna, "FCM", "fcm-" + UUID.randomUUID()).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/me/push-device").header("Authorization", bearer(anna.token())))
                .andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM push_devices WHERE account_id = ?", Integer.class,
                anna.accountId()));
        mvc.perform(put("/api/v1/me/push-device").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"FCM\", \"token\": \"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveProfilesAreRemindedBeforeLeavingTheFeed() throws Exception {
        User sleeper = user(uniqueCity(), "Соня");
        String deviceToken = "fcm-" + UUID.randomUUID();
        register(sleeper, "FCM", deviceToken).andExpect(status().isNoContent());
        jdbc.update("DELETE FROM account_activity_days WHERE account_id = ?", sleeper.accountId());
        jdbc.update("INSERT INTO account_activity_days (account_id, day) VALUES (?, current_date - 27)", sleeper.accountId());

        pushService.remindInactive();
        PushStub.Push reminder = PushStub.next(deviceToken, WAIT);
        assertNotNull(reminder, "3 days before the profile leaves the feed");
        assertTrue(reminder.body().contains("\"type\":\"inactivity_reminder\""), reminder.body());

        jdbc.update("INSERT INTO account_activity_days (account_id, day) VALUES (?, current_date - 26)", sleeper.accountId());
        pushService.remindInactive();
        assertNull(PushStub.next(deviceToken, 500), "no reminder between thresholds");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.dating.push.application.PushService pushService;

    private void waitUntilDeviceGone(String token) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (jdbc.queryForObject("SELECT count(*) FROM push_devices WHERE token = ?", Integer.class, token) == 0) {
                return;
            }
            Thread.sleep(50);
        }
        fail("dead token was not removed");
    }

    private User user(String city, String name) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return new User(token, UUID.fromString(json(get("/api/v1/me"), token, "$.id")),
                UUID.fromString(json(get("/api/v1/me/profile"), token, "$.id")));
    }

    private ResultActions register(User user, String provider, String token) throws Exception {
        return mvc.perform(put("/api/v1/me/push-device")
                .header("Authorization", bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"" + provider + "\", \"token\": \"" + token + "\"}"));
    }

    private MvcResult like(User from, User to) throws Exception {
        return mvc.perform(post("/api/v1/discovery/profiles/" + to.profileId() + "/like")
                        .header("Authorization", bearer(from.token())))
                .andExpect(status().isOk())
                .andReturn();
    }
}
