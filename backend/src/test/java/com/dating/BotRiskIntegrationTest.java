package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bot / fake signals: the automatic mass-message rule, the risk score and the moderators' risk queue. */
class BotRiskIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void sameTextWithContactsToManyChatsRestrictsMessagesAndRaisesRisk() throws Exception {
        String city = uniqueCity();
        User bot = user(city, "Бот");
        List<String> chats = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chats.add(match(user(city, "Жертва " + i), bot));
        }

        // A friendly copy-paste without contacts is not a bot.
        for (int i = 0; i < 3; i++) {
            send(bot, chats.get(i), "Привет! Как настроение?").andExpect(status().isOk());
        }
        String spam = "Скучно тут, пиши мне в телеграм t.me/bestgirl_bot";
        for (int i = 0; i < 3; i++) {
            send(bot, chats.get(i), spam).andExpect(status().isOk());
        }
        send(bot, chats.get(3), "привет").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.details.kind").value("MESSAGES"));
        as(bot.token(), get("/api/v1/me/restrictions"), null).andExpect(jsonPath("$[0].reason").value("BOT_MESSAGES"));

        String moderator = moderator();
        as(moderator, get("/api/v1/admin/accounts/" + bot.accountId() + "/risk"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals[*].code", hasItems("SAME_MESSAGE_TO_MANY", "CONTACTS_IN_MESSAGES", "BOT_MESSAGES")))
                .andExpect(jsonPath("$.level").value("HIGH"));
        as(moderator, get("/api/v1/admin/risk?minScore=60&limit=100"), null)
                .andExpect(jsonPath("$.items[*].accountId", hasItem(bot.accountId().toString())));
    }

    @Test
    void copiedProfilesAndContactsInProfileAreSignals() throws Exception {
        String city = uniqueCity();
        String about = "Люблю путешествия, кофе и долгие прогулки по набережной вечером";
        User original = user(city, "Оригинал");
        User copy = user(city, "Копия");
        putProfile(original.token(), "{\"aboutText\": \"" + about + "\"}").andExpect(status().isOk());
        putProfile(copy.token(), "{\"aboutText\": \"  " + about.toUpperCase() + " \", \"dailyStatusText\": \"инст @copy_cat_77\"}")
                .andExpect(status().isOk());

        String moderator = moderator();
        // Every test account uploads the same tiny PNG, so the photo duplicate signal fires too.
        as(moderator, get("/api/v1/admin/accounts/" + copy.accountId() + "/risk"), null)
                .andExpect(jsonPath("$.signals[*].code", hasItems("DUPLICATE_ABOUT", "CONTACTS_IN_PROFILE", "DUPLICATE_PHOTO")));
        as(moderator, get("/api/v1/admin/accounts/" + original.accountId() + "/risk"), null)
                .andExpect(jsonPath("$.signals[*].code", not(hasItem("CONTACTS_IN_PROFILE"))));

        // The report card shows the risk of the reported account.
        String reportId = JsonPath.read(body(as(original.token(), post("/api/v1/reports"),
                "{\"profileId\": \"" + copy.profileId() + "\", \"reason\": \"FAKE_PROFILE\"}")
                .andExpect(status().isCreated()).andReturn()), "$.id");
        as(moderator, get("/api/v1/admin/reports/" + reportId), null)
                .andExpect(jsonPath("$.reportedRiskScore").isNumber());

        as(copy.token(), get("/api/v1/admin/risk"), null).andExpect(status().isForbidden());
        as(moderator, get("/api/v1/admin/accounts/" + UUID.randomUUID() + "/risk"), null).andExpect(status().isNotFound());
    }

    @Test
    void signupAddressIsStoredOnlyAsAKeyedHash() throws Exception {
        User fresh = user(uniqueCity(), "Новичок");
        String hash = jdbc.queryForObject("SELECT ip_hash FROM account_signups WHERE account_id = ?", String.class,
                fresh.accountId());
        assertEquals(64, hash.length());
        assertEquals(-1, hash.indexOf("127.0.0.1"));
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

    private String match(User a, User b) throws Exception {
        as(a.token(), post("/api/v1/discovery/profiles/" + b.profileId() + "/like"), null).andExpect(status().isOk());
        MvcResult result = as(b.token(), post("/api/v1/discovery/profiles/" + a.profileId() + "/like"), null)
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(body(result), "$.matchId");
    }

    private ResultActions send(User from, String matchId, String text) throws Exception {
        return as(from.token(), post("/api/v1/matches/" + matchId + "/messages"),
                "{\"clientMessageId\": \"" + UUID.randomUUID() + "\", \"type\": \"TEXT\", \"text\": \"" + text + "\"}");
    }
}
