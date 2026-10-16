package com.dating.chat.api;

import com.dating.chat.application.ChatService;
import com.dating.chat.application.MatchViews;
import com.dating.common.security.AuthenticatedUser;
import com.dating.social.application.MatchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Matches and their chats. Unmatched / blocked matches answer 404 MATCH_NOT_FOUND for both people.
 */
@RestController
@RequestMapping("/api/v1/matches")
public class MatchController {

    private final MatchViews matchViews;
    private final MatchService matchService;
    private final ChatService chatService;

    public MatchController(MatchViews matchViews, MatchService matchService, ChatService chatService) {
        this.matchViews = matchViews;
        this.matchService = matchService;
        this.chatService = chatService;
    }

    @GetMapping
    public List<MatchDto> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return matchViews.list(user.accountId());
    }

    /** Unmatch: hides the chat for both and blocks new messages. */
    @DeleteMapping("/{matchId}")
    public ResponseEntity<Void> unmatch(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID matchId) {
        matchService.unmatch(user.accountId(), matchId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{matchId}/messages")
    public MessagePageDto messages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID matchId,
                                   @RequestParam(required = false) UUID before,
                                   @RequestParam(required = false) Integer limit) {
        return chatService.messages(user.accountId(), matchId, before, limit);
    }

    @PostMapping("/{matchId}/messages")
    public MessageDto send(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID matchId,
                           @Valid @RequestBody SendMessageRequest request) {
        return chatService.send(user.accountId(), matchId, request);
    }

    @PostMapping("/{matchId}/read")
    public ReadStateDto read(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID matchId,
                             @Valid @RequestBody ReadRequest request) {
        return chatService.markRead(user.accountId(), matchId, request.lastReadMessageId());
    }
}
