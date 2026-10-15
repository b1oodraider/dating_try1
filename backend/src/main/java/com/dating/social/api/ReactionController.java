package com.dating.social.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.social.application.ReactionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reactions to profiles from the feed. Profiles are addressed by profile id (account ids are never exposed).
 */
@RestController
@RequestMapping("/api/v1/discovery/profiles/{profileId}")
public class ReactionController {

    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    @PostMapping("/like")
    public ReactionResultDto like(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID profileId) {
        return reactionService.like(user.accountId(), profileId);
    }

    @PostMapping("/superlike")
    public ReactionResultDto superlike(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID profileId,
                                       @Valid @RequestBody SuperlikeRequest request) {
        return reactionService.superlike(user.accountId(), profileId, request.idempotencyKey());
    }

    @PostMapping("/pass")
    public ReactionResultDto pass(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID profileId) {
        return reactionService.pass(user.accountId(), profileId);
    }
}
