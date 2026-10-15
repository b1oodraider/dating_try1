package com.dating;

import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Configuration-dependent social behaviour, in its own Spring context: WHO_LIKED_ME is a premium feature here,
 * the message rate limit is tiny and the seed auto-reply bot is on with a short delay.
 */
@TestPropertySource(properties = {
        "app.economy.premium-features=TEST_PREMIUM_FEATURE,ADVANCED_FILTERS,EXCLUSION_WORDS,WHO_LIKED_ME",
        "app.chat.messages-per-minute=5",
        "app.testing.seed-auto-reply=true",
        "app.testing.seed-auto-reply-delay=200ms"
})
class SocialPremiumAndBotIntegrationTest extends IntegrationTestBase {

    @Test
    void whoLikedMeIsPremiumWhenConfiguredButTheCountIsAlwaysAvailable() throws Exception {
        String city = uniqueCity();
        String me = login(newPhone());
        publishWith(me, "Аня", city, List.of());
        String liker = login(newPhone());
        publishWith(liker, "Борис", city, List.of());
        mvc.perform(post("/api/v1/discovery/profiles/" + profileId(me) + "/like").header("Authorization", bearer(liker)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/me/likes/received").header("Authorization", bearer(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PREMIUM_REQUIRED"))
                .andExpect(jsonPath("$.details.feature").value("WHO_LIKED_ME"));
        mvc.perform(get("/api/v1/me/likes/received/count").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/v1/me/wallet").header("Authorization", bearer(me)))
                .andExpect(jsonPath("$.premiumFeatures", hasItem("WHO_LIKED_ME")));

        grantPremium(me, 7);
        mvc.perform(get("/api/v1/me/likes/received").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].profile.displayName").value("Борис"));
    }

    @Test
    void messagesAreRateLimitedPerAccountButRetriesAreNotCounted() throws Exception {
        String city = uniqueCity();
        String a = login(newPhone());
        publishWith(a, "Аня", city, List.of());
        String b = login(newPhone());
        publishWith(b, "Борис", city, List.of());
        like(a, profileId(b));
        String matchId = JsonPath.read(body(like(b, profileId(a)).andReturn()), "$.matchId");

        for (int i = 1; i <= 5; i++) {
            sendText(a, matchId, "m" + i).andExpect(status().isOk());
        }
        sendText(a, matchId, "m6").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        sendText(a, matchId, "m1").andExpect(status().isOk()); // a retry of a stored message is answered
        sendText(b, matchId, "ответ").andExpect(status().isOk()); // the limit is per account
    }

    @Test
    void seedProfilesAutoReplyToTestAccountsAfterTheDelay() throws Exception {
        String city = uniqueCity();
        String tester = login(newPhone());
        UUID testerId = UUID.fromString(json(get("/api/v1/me"), tester, "$.id"));
        jdbc.update("UPDATE accounts SET test_account = true WHERE id = ?", testerId);
        publishWith(tester, "Тестер", city, List.of());
        String seedId = createSeed(tester, city);

        MvcResult match = mvc.perform(post("/api/v1/testing/social/seed/" + seedId + "/match")
                        .header("Authorization", bearer(tester)))
                .andExpect(status().isOk()).andReturn();
        String matchId = JsonPath.read(body(match), "$.matchId");

        sendText(tester, matchId, "Привет, сид!").andExpect(status().isOk());
        List<String> texts = waitForMessages(tester, matchId, 2);
        assertEquals(2, texts.size(), "the seed answered");
        assertTrue(com.dating.testing.SeedAutoReplyBot.PHRASES.contains(texts.get(0)), texts.get(0));

        // A message written AS the seed (testing API) is not answered by the bot: seeds never answer seeds.
        mvc.perform(post("/api/v1/testing/social/matches/" + matchId + "/messages")
                        .header("Authorization", bearer(tester))
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"text\": \"Я сам\"}"))
                .andExpect(status().isOk());
        Thread.sleep(600);
        assertEquals(3, waitForMessages(tester, matchId, 3).size());
    }

    // ------------------------------------------------------------------ helpers

    private List<String> waitForMessages(String token, String matchId, int expected) throws Exception {
        List<String> texts = List.of();
        for (int attempt = 0; attempt < 50 && texts.size() < expected; attempt++) {
            Thread.sleep(100);
            MvcResult page = mvc.perform(get("/api/v1/matches/" + matchId + "/messages").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn();
            texts = JsonPath.read(body(page), "$.items[*].text");
        }
        return texts;
    }

    private String createSeed(String tester, String city) throws Exception {
        MvcResult created = mvc.perform(withJson(post("/api/v1/testing/profiles"),
                        "{\"profile\": {\"displayName\": \"Сид\", \"age\": 26, \"city\": \"" + city + "\"}}")
                        .header("Authorization", bearer(tester)))
                .andExpect(status().isCreated()).andReturn();
        String seedId = JsonPath.read(body(created), "$.accountId");
        mvc.perform(multipart("/api/v1/testing/profiles/" + seedId + "/photos")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(3, 3)))
                        .header("Authorization", bearer(tester)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/testing/profiles/" + seedId + "/publish").header("Authorization", bearer(tester)))
                .andExpect(status().isOk());
        return seedId;
    }

    private String profileId(String token) throws Exception {
        return json(get("/api/v1/me/profile"), token, "$.id");
    }

    private ResultActions like(String token, String profileId) throws Exception {
        return mvc.perform(post("/api/v1/discovery/profiles/" + profileId + "/like").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private ResultActions sendText(String token, String matchId, String text) throws Exception {
        return mvc.perform(withJson(post("/api/v1/matches/" + matchId + "/messages"),
                        "{\"clientMessageId\": \"" + text + "-" + token.substring(0, 6) + "\", \"type\": \"TEXT\", \"text\": \"" + text + "\"}")
                .header("Authorization", bearer(token)));
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8).content(json);
    }
}
