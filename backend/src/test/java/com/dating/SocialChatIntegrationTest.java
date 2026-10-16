package com.dating;

import com.dating.chat.realtime.SseEmitterRegistry;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.social.api.ReactionResultDto;
import com.dating.social.application.ReactionService;
import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Likes, matches, chats, blocks, reports, real-time events and the social testing API.
 * WHO_LIKED_ME is not a premium feature in the test profile, so "who liked me" is free here
 * (the gated case is in SocialPremiumAndBotIntegrationTest).
 */
class SocialChatIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ReactionService reactionService;

    @Autowired
    private SseEmitterRegistry sseRegistry;

    record User(String token, UUID accountId, UUID profileId) {
    }

    // ------------------------------------------------------------------ reactions

    @Test
    void likeRulesTargetMustBeVisibleSameKindAndNotMe() throws Exception {
        String city = uniqueCity();
        User me = user(city, "Аня");
        User other = user(city, "Борис");

        like(me, me).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_ACTION"));
        mvc.perform(post("/api/v1/discovery/profiles/" + UUID.randomUUID() + "/like").header("Authorization", bearer(me.token())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));

        User draft = unpublished();
        like(me, draft).andExpect(status().isNotFound());
        User tester = testUser(city, "Тест");
        like(me, tester).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
        like(tester, me).andExpect(status().isNotFound());

        like(me, other).andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.matchId").doesNotExist());
        like(me, other).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(false)); // idempotent
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM profile_reactions WHERE actor_account_id = ?",
                Integer.class, me.accountId()));
        pass(me, other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_LIKED"));

        // Hidden profile cannot be liked.
        mvc.perform(post("/api/v1/me/profile/hide").header("Authorization", bearer(other.token()))).andExpect(status().isOk());
        User third = user(city, "Вера");
        like(third, other).andExpect(status().isNotFound());
    }

    @Test
    void mutualLikeCreatesExactlyOneMatchEvenConcurrently() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        like(a, b).andExpect(jsonPath("$.matched").value(false));
        MvcResult mutual = like(b, a).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(true)).andReturn();
        String matchId = JsonPath.read(body(mutual), "$.matchId");
        like(a, b).andExpect(jsonPath("$.matched").value(true)).andExpect(jsonPath("$.matchId").value(matchId));

        mvc.perform(get("/api/v1/matches").header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(matchId))
                .andExpect(jsonPath("$[0].profile.id").value(b.profileId().toString()))
                .andExpect(jsonPath("$[0].profile.displayName").value("Борис"))
                .andExpect(jsonPath("$[0].profile.accountId").doesNotExist())
                .andExpect(jsonPath("$[0].lastMessage").value(nullValue()))
                .andExpect(jsonPath("$[0].unreadCount").value(0));
        mvc.perform(get("/api/v1/matches").header("Authorization", bearer(b.token())))
                .andExpect(jsonPath("$[0].profile.id").value(a.profileId().toString()));

        // Both like each other at the same moment, several pairs: exactly one match per pair.
        int pairs = 4;
        List<User[]> users = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            users.add(new User[]{user(city, "Пара" + i + "а"), user(city, "Пара" + i + "б")});
        }
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReactionResultDto>> results = new ArrayList<>();
        for (User[] pair : users) {
            results.add(pool.submit(() -> {
                start.await();
                return reactionService.like(pair[0].accountId(), pair[1].profileId());
            }));
            results.add(pool.submit(() -> {
                start.await();
                return reactionService.like(pair[1].accountId(), pair[0].profileId());
            }));
        }
        start.countDown();
        for (int i = 0; i < pairs; i++) {
            ReactionResultDto first = results.get(2 * i).get();
            ReactionResultDto second = results.get(2 * i + 1).get();
            assertTrue(first.matched() ^ second.matched(), "exactly one of the two likes reports the match");
            User[] pair = users.get(i);
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM matches
                    WHERE account_low = LEAST(?::uuid, ?::uuid) AND account_high = GREATEST(?::uuid, ?::uuid)
                    """, Integer.class, pair[0].accountId(), pair[1].accountId(), pair[0].accountId(), pair[1].accountId()));
        }
        pool.shutdown();
    }

    @Test
    void superlikeSpendsOneSuperlikeIdempotentlyAndIsHighlighted() throws Exception {
        String city = uniqueCity();
        User me = user(city, "Аня");
        User target = user(city, "Борис");
        User other = user(city, "Вера");

        superlike(me, target, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").exists());
        superlike(me, target, "{\"idempotencyKey\": \"k1\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.details.itemCode").value("SUPERLIKE"));

        economyService.grant(me.accountId(), RewardSource.PURCHASE, "order", "purchase:" + UUID.randomUUID(),
                List.of(new Reward.Item("SUPERLIKE", 2)));
        superlike(me, target, "{\"idempotencyKey\": \"k1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));
        assertEquals(1, economyService.balance(me.accountId(), "SUPERLIKE"));
        superlike(me, target, "{\"idempotencyKey\": \"k1\"}").andExpect(status().isOk()); // retry
        superlike(me, target, "{\"idempotencyKey\": \"k2\"}").andExpect(status().isOk()); // already super-liked
        like(me, target).andExpect(status().isOk());                                    // stays a super like
        assertEquals(1, economyService.balance(me.accountId(), "SUPERLIKE"), "a super-liked target is charged once");

        // Upgrading a like to a super like spends.
        like(me, other).andExpect(status().isOk());
        superlike(me, other, "{\"idempotencyKey\": \"k1\"}").andExpect(status().isOk());
        assertEquals(0, economyService.balance(me.accountId(), "SUPERLIKE"));

        mvc.perform(get("/api/v1/me/likes/received").header("Authorization", bearer(target.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].profile.id").value(me.profileId().toString()))
                .andExpect(jsonPath("$.items[0].superlike").value(true));
    }

    @Test
    void discoveryHidesLikedPassedAndBlockedProfilesAndPassesExpire() throws Exception {
        String city = uniqueCity();
        User me = user(city, "Аня");
        User liked = user(city, "Лайк");
        User passed = user(city, "Пасс");
        User blocker = user(city, "Блок");
        User blockedByMe = user(city, "Скрыт");
        User fresh = user(city, "Новый");
        assertEquals(5, feed(me, city).size());

        like(me, liked).andExpect(status().isOk());
        pass(me, passed).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(false));
        block(blocker, me).andExpect(status().isNoContent());
        block(me, blockedByMe).andExpect(status().isNoContent());
        block(me, blockedByMe).andExpect(status().isNoContent()); // idempotent

        assertEquals(List.of(fresh.profileId().toString()), feed(me, city));
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(me.token())).queryParam("city", city))
                .andExpect(jsonPath("$.totalElements").value(1));
        assertFalse(feed(blocker, city).contains(me.profileId().toString()), "blocks hide both ways");
        assertFalse(feed(blockedByMe, city).contains(me.profileId().toString()));
        like(blocker, me).andExpect(status().isNotFound());
        like(me, blocker).andExpect(status().isNotFound());

        // A pass expires after app.social.pass-ttl (30 days by default).
        jdbc.update("UPDATE profile_reactions SET reacted_at = now() - interval '31 days' WHERE actor_account_id = ?",
                me.accountId());
        assertEquals(List.of(passed.profileId().toString(), fresh.profileId().toString()).stream().sorted().toList(),
                feed(me, city).stream().sorted().toList());
        pass(me, passed).andExpect(status().isOk()); // a repeated pass restarts the TTL
        assertEquals(List.of(fresh.profileId().toString()), feed(me, city));
        // Changing your mind: a passed profile can still be liked.
        like(me, passed).andExpect(status().isOk());
    }

    @Test
    void receivedLikesListAndCountWithoutPremiumWhileFeatureIsNotConfigured() throws Exception {
        String city = uniqueCity();
        User me = user(city, "Аня");
        User liker = user(city, "Борис");
        User superliker = user(city, "Вера");
        User blocked = user(city, "Гена");

        mvc.perform(get("/api/v1/me/likes/received/count").header("Authorization", bearer(me.token())))
                .andExpect(jsonPath("$.count").value(0));
        like(liker, me).andExpect(status().isOk());
        reactionService.reactForTesting(superliker.accountId(), me.accountId(), com.dating.social.domain.ReactionType.SUPERLIKE);
        like(blocked, me).andExpect(status().isOk());
        block(me, blocked).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/me/likes/received").header("Authorization", bearer(me.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].profile.id").value(superliker.profileId().toString()))
                .andExpect(jsonPath("$.items[0].superlike").value(true))
                .andExpect(jsonPath("$.items[1].profile.id").value(liker.profileId().toString()))
                .andExpect(jsonPath("$.items[1].superlike").value(false))
                .andExpect(jsonPath("$.items[1].likedAt").exists());
        mvc.perform(get("/api/v1/me/likes/received/count").header("Authorization", bearer(me.token())))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.superlikeCount").value(1));

        // Answered likes leave the list: a like back is a match, a pass hides.
        like(me, liker).andExpect(jsonPath("$.matched").value(true));
        pass(me, superliker).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/likes/received/count").header("Authorization", bearer(me.token())))
                .andExpect(jsonPath("$.count").value(0));
    }

    // ------------------------------------------------------------------ chats

    @Test
    void messagesOnlyBetweenMatchedUsersWithValidation() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        User stranger = user(city, "Чужой");
        UUID matchId = match(a, b);

        sendText(stranger, matchId, "c1", "Привет").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
        mvc.perform(get("/api/v1/matches/" + matchId + "/messages").header("Authorization", bearer(stranger.token())))
                .andExpect(status().isNotFound());
        sendText(a, UUID.randomUUID(), "c1", "Привет").andExpect(status().isNotFound());

        sendText(a, matchId, "c1", "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.text").exists());
        sendText(a, matchId, "c1", "я".repeat(2001)).andExpect(status().isBadRequest());
        send(a, matchId, "{\"clientMessageId\": \"c1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.type").exists());

        sendText(a, matchId, "c1", "  Привет! " + "я".repeat(1990) + "  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.type").value("TEXT"))
                .andExpect(jsonPath("$.text").value("Привет! " + "я".repeat(1990)))
                .andExpect(jsonPath("$.clientMessageId").value("c1"));
        mvc.perform(get("/api/v1/matches/" + matchId + "/messages").header("Authorization", bearer(b.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].mine").value(false))
                .andExpect(jsonPath("$.items[0].clientMessageId").value(nullValue()))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
    }

    @Test
    void retriedClientMessageIdIsStoredOnce() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        UUID matchId = match(a, b);

        String first = JsonPath.read(body(sendText(a, matchId, "retry-1", "Привет").andExpect(status().isOk()).andReturn()), "$.id");
        String retry = JsonPath.read(body(sendText(a, matchId, "retry-1", "Привет").andExpect(status().isOk()).andReturn()), "$.id");
        assertEquals(first, retry);
        // The same client id from the other participant is a different message.
        String fromB = JsonPath.read(body(sendText(b, matchId, "retry-1", "Привет!").andExpect(status().isOk()).andReturn()), "$.id");
        assertNotEquals(first, fromB);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE match_id = ?", Integer.class, matchId));
    }

    @Test
    void messagesArePagedNewestFirstWithACursor() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        UUID matchId = match(a, b);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            ids.add(sendTextId(i % 2 == 0 ? b : a, matchId, "m" + i));
        }

        MvcResult page1 = page(a, matchId, null, 3)
                .andExpect(jsonPath("$.items[*].text", contains("m7", "m6", "m5")))
                .andExpect(jsonPath("$.nextBefore").value(ids.get(4))).andReturn();
        MvcResult page2 = page(a, matchId, JsonPath.read(body(page1), "$.nextBefore"), 3)
                .andExpect(jsonPath("$.items[*].text", contains("m4", "m3", "m2"))).andReturn();
        page(a, matchId, JsonPath.read(body(page2), "$.nextBefore"), 3)
                .andExpect(jsonPath("$.items[*].text", contains("m1")))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
        page(a, matchId, UUID.randomUUID().toString(), 3).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));

        mvc.perform(get("/api/v1/matches").header("Authorization", bearer(a.token())))
                .andExpect(jsonPath("$[0].lastMessage.text").value("m7"))
                .andExpect(jsonPath("$[0].lastMessage.mine").value(true));
    }

    @Test
    void readReceiptsMoveForwardOnlyAndShowInTheMatch() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        UUID matchId = match(a, b);
        String m1 = sendTextId(a, matchId, "раз");
        String m2 = sendTextId(a, matchId, "два");
        sendTextId(a, matchId, "три");

        matchOf(b).andExpect(jsonPath("$[0].unreadCount").value(3))
                .andExpect(jsonPath("$[0].otherLastReadAt").value(nullValue()));
        read(b, matchId, m2).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadMessageId").value(m2))
                .andExpect(jsonPath("$.unreadCount").value(1));
        read(b, matchId, m1).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadMessageId").value(m2)); // never moves back

        matchOf(b).andExpect(jsonPath("$[0].unreadCount").value(1))
                .andExpect(jsonPath("$[0].myLastReadMessageId").value(m2));
        matchOf(a).andExpect(jsonPath("$[0].otherLastReadMessageId").value(m2))
                .andExpect(jsonPath("$[0].otherLastReadAt").isNotEmpty())
                .andExpect(jsonPath("$[0].unreadCount").value(0));
        read(a, matchId, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void stickersRequireAnOwnedPackAndAKnownSticker() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        UUID matchId = match(a, b);

        sendSticker(a, matchId, "s1", "STICKERS_STUB_CATS", "cat_wave").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_OWNED"));
        economyService.grant(a.accountId(), RewardSource.ADMIN, "test", "test:" + UUID.randomUUID(),
                List.of(new Reward.Item("STICKERS_STUB_CATS", 1), new Reward.Item("FRAME_STUB_GOLD", 1)));
        sendSticker(a, matchId, "s1", "STICKERS_STUB_CATS", "rocket").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_STICKER"));
        sendSticker(a, matchId, "s1", "FRAME_STUB_GOLD", "cat_wave").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_STICKER_PACK"));
        sendSticker(a, matchId, "s1", "NO_SUCH_PACK", "cat_wave").andExpect(status().isNotFound());
        send(a, matchId, "{\"clientMessageId\": \"s1\", \"type\": \"STICKER\", \"stickerPackCode\": \"STICKERS_STUB_CATS\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.sticker").exists());

        sendSticker(a, matchId, "s1", "STICKERS_STUB_CATS", "cat_wave").andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("STICKER"))
                .andExpect(jsonPath("$.stickerPackCode").value("STICKERS_STUB_CATS"))
                .andExpect(jsonPath("$.sticker").value("cat_wave"))
                .andExpect(jsonPath("$.text").value(nullValue()));
        // The receiver does not need to own the pack to see the sticker.
        page(b, matchId, null, 10).andExpect(jsonPath("$.items[0].sticker").value("cat_wave"));
    }

    @Test
    void unmatchHidesTheChatForBothAndBlocksMessages() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        UUID matchId = match(a, b);
        sendTextId(a, matchId, "Привет");

        mvc.perform(delete("/api/v1/matches/" + matchId).header("Authorization", bearer(b.token())))
                .andExpect(status().isNoContent());
        matchOf(a).andExpect(jsonPath("$", hasSize(0)));
        matchOf(b).andExpect(jsonPath("$", hasSize(0)));
        sendText(a, matchId, "x", "Ты где?").andExpect(status().isNotFound());
        page(a, matchId, null, 10).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/matches/" + matchId).header("Authorization", bearer(a.token())))
                .andExpect(status().isNotFound());
        // Likes stay, so they do not meet again and a repeated like does not resurrect the match.
        assertFalse(feed(a, city).contains(b.profileId().toString()));
        like(a, b).andExpect(jsonPath("$.matched").value(false));
    }

    @Test
    void blockClosesTheMatchAndReportsAreStoredForModeration() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        User c = user(city, "Вера");
        UUID matchId = match(a, b);
        UUID otherMatch = match(a, c);
        String rude = sendTextId(b, matchId, "Грубость");
        String fromC = sendTextId(c, otherMatch, "Привет");

        report(a, "{\"profileId\": \"" + b.profileId() + "\", \"messageId\": \"" + rude + "\", \"reason\": \"HARASSMENT\","
                + " \"comment\": \"  Оскорбления  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
        report(a, "{\"profileId\": \"" + b.profileId() + "\", \"messageId\": \"" + fromC + "\", \"reason\": \"SPAM\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
        report(a, "{\"profileId\": \"" + b.profileId() + "\", \"reason\": \"NOPE\"}").andExpect(status().isBadRequest());
        report(a, "{\"profileId\": \"" + b.profileId() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").exists());
        report(a, "{\"profileId\": \"" + a.profileId() + "\", \"reason\": \"SPAM\"}").andExpect(status().isBadRequest());

        assertEquals(List.of("HARASSMENT|Грубость|Оскорбления|NEW"), jdbc.query("""
                        SELECT reason, message_snapshot, comment, status FROM reports
                        WHERE reporter_account_id = ? AND reported_account_id = ?
                        """, (rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4),
                a.accountId(), b.accountId()));

        block(a, b).andExpect(status().isNoContent());
        matchOf(b).andExpect(jsonPath("$", hasSize(0)));
        matchOf(a).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(otherMatch.toString()));
        sendText(b, matchId, "x", "Эй").andExpect(status().isNotFound());
        like(b, a).andExpect(status().isNotFound());
        // Reporting after blocking still works (the profile still exists).
        report(a, "{\"profileId\": \"" + b.profileId() + "\", \"reason\": \"OTHER\"}").andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ real time

    @Test
    void eventStreamNeedsATokenOrAOneTimeTicketAndDeliversEvents() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");

        mvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/events").queryParam("ticket", "nope"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/events").queryParam("access_token", a.token()))
                .andExpect(status().isUnauthorized()); // the session token is never accepted in the URL
        mvc.perform(post("/api/v1/events/ticket")).andExpect(status().isUnauthorized());

        MvcResult ticket = mvc.perform(post("/api/v1/events/ticket").header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();
        String rawTicket = JsonPath.read(body(ticket), "$.ticket");
        MvcResult streamA = mvc.perform(get("/api/v1/events").queryParam("ticket", rawTicket))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(get("/api/v1/events").queryParam("ticket", rawTicket))
                .andExpect(status().isUnauthorized()); // single use
        MvcResult streamB = mvc.perform(get("/api/v1/events").header("Authorization", bearer(b.token())))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertEquals(1, sseRegistry.connectionCount(a.accountId()));
        assertTrue(streamA.getResponse().getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        assertTrue(content(streamA).contains("event:ready"));

        like(b, a).andExpect(status().isOk());
        assertTrue(content(streamA).contains("event:like_received"), content(streamA));
        UUID matchId = match(a, b);
        assertTrue(content(streamA).contains("event:match\n"), content(streamA));
        assertTrue(content(streamB).contains("\"displayName\":\"Аня\""), "B gets the match with A's profile");

        String messageId = sendTextId(b, matchId, "Привет из потока");
        assertTrue(content(streamA).contains("event:message"));
        assertTrue(content(streamA).contains("Привет из потока"));
        read(a, matchId, messageId).andExpect(status().isOk());
        assertTrue(content(streamB).contains("event:read"));
        assertTrue(content(streamB).contains(messageId));

        mvc.perform(delete("/api/v1/matches/" + matchId).header("Authorization", bearer(a.token())))
                .andExpect(status().isNoContent());
        assertTrue(content(streamB).contains("event:match_closed"));

        sseRegistry.sendHeartbeats();
        assertTrue(content(streamA).contains("event:heartbeat"));
        sseRegistry.closeAll(a.accountId());
        sseRegistry.closeAll(b.accountId());
        assertEquals(0, sseRegistry.connectionCount(a.accountId()));
    }

    // ------------------------------------------------------------------ testing API

    @Test
    void testingApiLetsATesterBeLikedMatchedAndMessagedBySeedProfiles() throws Exception {
        String city = uniqueCity();
        User tester = testUser(city, "Тестер");
        User seed = seed(tester, city, "Сид");
        User seed2 = seed(tester, city, "Сид-2");
        String base = "/api/v1/testing/social";

        testing(user(city, "Реальный"), post(base + "/seed/" + seed.accountId() + "/like-me"), null, 403)
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_REQUIRED"));
        testing(tester, post(base + "/seed/" + tester.accountId() + "/like-me"), null, 404)
                .andExpect(jsonPath("$.code").value("SEED_PROFILE_NOT_FOUND"));

        testing(tester, post(base + "/seed/" + seed.accountId() + "/like-me"), "{\"superlike\": true}", 200)
                .andExpect(jsonPath("$.matched").value(false));
        mvc.perform(get("/api/v1/me/likes/received").header("Authorization", bearer(tester.token())))
                .andExpect(jsonPath("$.items[0].profile.id").value(seed.profileId().toString()))
                .andExpect(jsonPath("$.items[0].superlike").value(true));
        // Liking back makes it a match.
        like(tester, seed).andExpect(jsonPath("$.matched").value(true));

        MvcResult instant = testing(tester, post(base + "/seed/" + seed2.accountId() + "/match"), null, 200)
                .andExpect(jsonPath("$.matched").value(true)).andReturn();
        String matchId = JsonPath.read(body(instant), "$.matchId");
        testing(tester, post(base + "/matches/" + matchId + "/messages"), "{\"text\": \"Привет, я сид\"}", 200)
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.text").value("Привет, я сид"));
        matchOf(tester).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(matchId))
                .andExpect(jsonPath("$[0].unreadCount").value(1));
        testing(tester, post(base + "/matches/" + UUID.randomUUID() + "/messages"), "{\"text\": \"x\"}", 404);

        // Reset: passes only, then everything (matches stay).
        User passed = seed(tester, city, "Сид-3");
        pass(tester, passed).andExpect(status().isOk());
        assertFalse(feed(tester, city).contains(passed.profileId().toString()));
        testing(tester, delete(base + "/passes"), null, 200).andExpect(jsonPath("$.deleted").value(1));
        assertTrue(feed(tester, city).contains(passed.profileId().toString()));
        testing(tester, delete(base + "/reactions"), null, 200).andExpect(jsonPath("$.deleted").value(2));
        assertTrue(feed(tester, city).contains(seed.profileId().toString()));
        matchOf(tester).andExpect(jsonPath("$", hasSize(2)));
    }

    // ------------------------------------------------------------------ account deletion

    @Test
    void deletingAnAccountRemovesItsSocialDataAndClosesItsStreams() throws Exception {
        String city = uniqueCity();
        User a = user(city, "Аня");
        User b = user(city, "Борис");
        User c = user(city, "Вера");
        UUID matchId = match(a, b);
        sendTextId(a, matchId, "Пока");
        like(a, c).andExpect(status().isOk());
        block(a, c).andExpect(status().isNoContent());
        report(a, "{\"profileId\": \"" + b.profileId() + "\", \"reason\": \"SPAM\"}").andExpect(status().isCreated());
        report(b, "{\"profileId\": \"" + a.profileId() + "\", \"reason\": \"FAKE_PROFILE\"}").andExpect(status().isCreated());
        MvcResult streamA = mvc.perform(get("/api/v1/events").header("Authorization", bearer(a.token())))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult streamB = mvc.perform(get("/api/v1/events").header("Authorization", bearer(b.token())))
                .andExpect(request().asyncStarted()).andReturn();
        assertEquals(1, sseRegistry.connectionCount(a.accountId()));

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(a.token()))).andExpect(status().isNoContent());

        assertEquals(0, sseRegistry.connectionCount(a.accountId()));
        assertTrue(content(streamB).contains("event:match_closed"));
        matchOf(b).andExpect(jsonPath("$", hasSize(0)));
        UUID id = a.accountId();
        assertEquals(0, count("SELECT count(*) FROM profile_reactions WHERE actor_account_id = ? OR target_account_id = ?", id, id));
        assertEquals(0, count("SELECT count(*) FROM account_blocks WHERE blocker_account_id = ? OR blocked_account_id = ?", id, id));
        assertEquals(0, count("SELECT count(*) FROM matches WHERE account_low = ? OR account_high = ?", id, id));
        assertEquals(0, count("SELECT count(*) FROM chat_messages WHERE sender_account_id = ?", id));
        assertEquals(0, count("SELECT count(*) FROM reports WHERE reporter_account_id = ?", id));
        assertEquals(1, count("SELECT count(*) FROM reports WHERE reported_account_id = ?", id), "reports about the account are kept");
        sseRegistry.closeAll(b.accountId());
        assertNotNull(streamA);
    }

    // ------------------------------------------------------------------ helpers

    private User user(String city, String name) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return me(token);
    }

    /** A test account (as if its phone were allowlisted): isolated from real users. */
    private User testUser(String city, String name) throws Exception {
        String token = login(newPhone());
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        jdbc.update("UPDATE accounts SET test_account = true WHERE id = ?", accountId);
        publishWith(token, name, city, List.of());
        return me(token);
    }

    private User unpublished() throws Exception {
        return me(login(newPhone()));
    }

    private User me(String token) throws Exception {
        return new User(token, UUID.fromString(json(get("/api/v1/me"), token, "$.id")),
                UUID.fromString(json(get("/api/v1/me/profile"), token, "$.id")));
    }

    /** A published seed profile (phone-less test account), created through the testing API. */
    protected User seed(User tester, String city, String name) throws Exception {
        MvcResult created = testing(tester, post("/api/v1/testing/profiles"),
                "{\"profile\": {\"displayName\": \"" + name + "\", \"age\": 27, \"city\": \"" + city + "\"}}", 201)
                .andReturn();
        String seedId = JsonPath.read(body(created), "$.accountId");
        String profileId = JsonPath.read(body(created), "$.profile.id");
        mvc.perform(multipart("/api/v1/testing/profiles/" + seedId + "/photos")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(3, 3)))
                        .header("Authorization", bearer(tester.token())))
                .andExpect(status().isOk());
        testing(tester, post("/api/v1/testing/profiles/" + seedId + "/publish"), null, 200);
        return new User(null, UUID.fromString(seedId), UUID.fromString(profileId));
    }

    private UUID match(User a, User b) throws Exception {
        like(a, b).andExpect(status().isOk());
        MvcResult result = like(b, a).andExpect(jsonPath("$.matched").value(true)).andReturn();
        return UUID.fromString(JsonPath.read(body(result), "$.matchId"));
    }

    private ResultActions like(User from, User to) throws Exception {
        return mvc.perform(post("/api/v1/discovery/profiles/" + to.profileId() + "/like")
                .header("Authorization", bearer(from.token())));
    }

    private ResultActions pass(User from, User to) throws Exception {
        return mvc.perform(post("/api/v1/discovery/profiles/" + to.profileId() + "/pass")
                .header("Authorization", bearer(from.token())));
    }

    private ResultActions superlike(User from, User to, String json) throws Exception {
        return mvc.perform(withJson(post("/api/v1/discovery/profiles/" + to.profileId() + "/superlike"), json)
                .header("Authorization", bearer(from.token())));
    }

    private ResultActions block(User from, User to) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + to.profileId() + "/block").header("Authorization", bearer(from.token())));
    }

    private ResultActions report(User from, String json) throws Exception {
        return mvc.perform(withJson(post("/api/v1/reports"), json).header("Authorization", bearer(from.token())));
    }

    private ResultActions send(User from, UUID matchId, String json) throws Exception {
        return mvc.perform(withJson(post("/api/v1/matches/" + matchId + "/messages"), json)
                .header("Authorization", bearer(from.token())));
    }

    private ResultActions sendText(User from, UUID matchId, String clientId, String text) throws Exception {
        return send(from, matchId, "{\"clientMessageId\": \"" + clientId + "\", \"type\": \"TEXT\", \"text\": \"" + text + "\"}");
    }

    private String sendTextId(User from, UUID matchId, String text) throws Exception {
        MvcResult result = sendText(from, matchId, UUID.randomUUID().toString(), text).andExpect(status().isOk()).andReturn();
        return JsonPath.read(body(result), "$.id");
    }

    private ResultActions sendSticker(User from, UUID matchId, String clientId, String pack, String sticker) throws Exception {
        return send(from, matchId, "{\"clientMessageId\": \"" + clientId + "\", \"type\": \"STICKER\", \"stickerPackCode\": \""
                + pack + "\", \"sticker\": \"" + sticker + "\"}");
    }

    private ResultActions page(User viewer, UUID matchId, String before, int limit) throws Exception {
        var request = get("/api/v1/matches/" + matchId + "/messages")
                .header("Authorization", bearer(viewer.token()))
                .queryParam("limit", String.valueOf(limit));
        if (before != null) {
            request.queryParam("before", before);
        }
        return mvc.perform(request);
    }

    private ResultActions read(User reader, UUID matchId, String messageId) throws Exception {
        return mvc.perform(withJson(post("/api/v1/matches/" + matchId + "/read"), "{\"lastReadMessageId\": \"" + messageId + "\"}")
                .header("Authorization", bearer(reader.token())));
    }

    private ResultActions matchOf(User viewer) throws Exception {
        return mvc.perform(get("/api/v1/matches").header("Authorization", bearer(viewer.token()))).andExpect(status().isOk());
    }

    private List<String> feed(User viewer, String city) throws Exception {
        MvcResult result = discovery(viewer.token(), city, null, null, 50).andReturn();
        return JsonPath.read(body(result), "$.items[*].id");
    }

    private ResultActions testing(User caller, MockHttpServletRequestBuilder request, String json, int expectedStatus)
            throws Exception {
        if (json != null) {
            withJson(request, json);
        }
        return mvc.perform(request.header("Authorization", bearer(caller.token()))).andExpect(status().is(expectedStatus));
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8).content(json);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String content(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
