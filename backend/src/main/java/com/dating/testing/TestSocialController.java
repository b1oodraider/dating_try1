package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.chat.api.MessageDto;
import com.dating.chat.api.SendMessageRequest;
import com.dating.chat.application.ChatService;
import com.dating.chat.domain.MessageType;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.social.api.ReactionResultDto;
import com.dating.social.application.MatchService;
import com.dating.social.application.ReactionService;
import com.dating.social.domain.MatchInfo;
import com.dating.social.domain.ReactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * QA tools for likes, matches and chats with seed profiles (which cannot log in themselves).
 * Test accounts only; the endpoints exist only with app.testing.enabled=true.
 */
@RestController
@RequestMapping("/api/v1/testing/social")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestSocialController {

    /** @param superlike the seed uses a super like (free for seeds) */
    public record LikeMeRequest(Boolean superlike) {
    }

    /**
     * Same as a regular message; clientMessageId may be omitted (generated) and type defaults to TEXT.
     */
    public record SeedMessageRequest(
            @Size(max = 64) String clientMessageId,
            MessageType type,
            @Size(max = 4000) String text,
            @Size(max = 50) String stickerPackCode,
            @Size(max = 50) String sticker
    ) {
    }

    private final AccountService accountService;
    private final ReactionService reactionService;
    private final MatchService matchService;
    private final ChatService chatService;

    public TestSocialController(AccountService accountService, ReactionService reactionService,
                                MatchService matchService, ChatService chatService) {
        this.accountService = accountService;
        this.reactionService = reactionService;
        this.matchService = matchService;
        this.chatService = chatService;
    }

    /** The seed profile likes (or super likes) me; if I already liked it, this is a match. */
    @PostMapping("/seed/{seedAccountId}/like-me")
    public ReactionResultDto likeMe(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID seedAccountId,
                                    @RequestBody(required = false) LikeMeRequest request) {
        UUID me = requireTestAccount(user);
        requireSeed(seedAccountId);
        boolean superlike = request != null && Boolean.TRUE.equals(request.superlike());
        return reactionService.reactForTesting(seedAccountId, me, superlike ? ReactionType.SUPERLIKE : ReactionType.LIKE);
    }

    /** Instant match with a seed profile (both like each other). */
    @PostMapping("/seed/{seedAccountId}/match")
    public ReactionResultDto match(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID seedAccountId) {
        UUID me = requireTestAccount(user);
        requireSeed(seedAccountId);
        return ReactionResultDto.matched(reactionService.matchForTesting(me, seedAccountId));
    }

    /** The seed profile of my match writes to me. Returns the message as I see it. */
    @PostMapping("/matches/{matchId}/messages")
    public MessageDto sendAsSeed(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID matchId,
                                 @Valid @RequestBody SeedMessageRequest request) {
        UUID me = requireTestAccount(user);
        MatchInfo match = matchService.requireActive(matchId, me);
        UUID seed = match.other(me);
        requireSeed(seed);
        String clientMessageId = request.clientMessageId() == null || request.clientMessageId().isBlank()
                ? "testing:" + UUID.randomUUID() : request.clientMessageId();
        MessageType type = request.type() == null ? MessageType.TEXT : request.type();
        MessageDto sent = chatService.send(seed, matchId, new SendMessageRequest(clientMessageId, type,
                request.text(), request.stickerPackCode(), request.sticker()));
        return new MessageDto(sent.id(), sent.matchId(), false, sent.type(), sent.text(), sent.stickerPackCode(),
                sent.sticker(), null, sent.createdAt());
    }

    /** My passes are forgotten: those profiles come back to my feed. */
    @DeleteMapping("/passes")
    public Map<String, Integer> resetPasses(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("deleted", reactionService.resetReactions(requireTestAccount(user), true));
    }

    /** All my likes, super likes and passes are forgotten (matches stay). */
    @DeleteMapping("/reactions")
    public Map<String, Integer> resetReactions(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("deleted", reactionService.resetReactions(requireTestAccount(user), false));
    }

    private UUID requireTestAccount(AuthenticatedUser user) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
        return user.accountId();
    }

    private void requireSeed(UUID accountId) {
        if (!accountService.isActiveSeedAccount(accountId)) {
            throw ApiException.notFound("SEED_PROFILE_NOT_FOUND", "Тестовый профиль не найден");
        }
    }
}
