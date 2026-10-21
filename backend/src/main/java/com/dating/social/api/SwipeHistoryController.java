package com.dating.social.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.profiles.api.PublicProfileDto;
import com.dating.social.application.ReactionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Going back in the feed: undo the last swipe, bring passed profiles back (docs/matching.md, part 4). */
@RestController
public class SwipeHistoryController {

    private final ReactionService reactionService;

    public SwipeHistoryController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    @PostMapping("/api/v1/me/reactions/undo")
    public PublicProfileDto undo(@AuthenticationPrincipal AuthenticatedUser user) {
        return reactionService.undoLastReaction(user.accountId());
    }

    // shortcut: feedEmpty comes from the client (worst case: a free refresh once a day anyway); check on the server if abused.
    @PostMapping("/api/v1/me/passes/refresh")
    public Map<String, Integer> refreshPasses(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam(defaultValue = "false") boolean feedEmpty) {
        return Map.of("restored", reactionService.refreshPasses(user.accountId(), feedEmpty));
    }
}
