package com.dating.safety.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.safety.application.SanctionService;
import com.dating.safety.domain.RestrictionKind;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The user's own active restrictions, so the app can explain why likes or messages do not work and until when.
 */
@RestController
public class RestrictionController {

    /** @param endsAt null = until lifted by moderation */
    public record MyRestriction(RestrictionKind kind, String reason, Instant startsAt, Instant endsAt) {
    }

    private final SanctionService sanctionService;

    public RestrictionController(SanctionService sanctionService) {
        this.sanctionService = sanctionService;
    }

    @GetMapping("/api/v1/me/restrictions")
    public List<MyRestriction> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return sanctionService.active(user.accountId()).stream()
                .map(r -> new MyRestriction(r.kind(), r.reason(), r.startsAt(), r.endsAt()))
                .toList();
    }
}
