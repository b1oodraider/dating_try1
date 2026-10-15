package com.dating.social.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.social.application.ReceivedLikesService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Who liked me". The list is gated by the premium feature WHO_LIKED_ME; the count is always available.
 */
@RestController
@RequestMapping("/api/v1/me/likes/received")
public class ReceivedLikesController {

    private final ReceivedLikesService receivedLikesService;

    public ReceivedLikesController(ReceivedLikesService receivedLikesService) {
        this.receivedLikesService = receivedLikesService;
    }

    @GetMapping
    public ReceivedLikesPageDto list(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     @RequestParam(required = false, defaultValue = "20") int limit) {
        return receivedLikesService.list(user.accountId(), page, limit);
    }

    @GetMapping("/count")
    public ReceivedLikesCountDto count(@AuthenticationPrincipal AuthenticatedUser user) {
        return receivedLikesService.count(user.accountId());
    }
}
