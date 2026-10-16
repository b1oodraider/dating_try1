package com.dating.social.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.social.application.ModerationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Report queue for moderators. Every call requires the MODERATOR or ADMIN role (403 ROLE_REQUIRED).
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ModerationController {

    private final ModerationService moderationService;

    public ModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping
    public ModerationPageDto queue(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int limit) {
        return moderationService.queue(user.accountId(), status, page, limit);
    }

    @GetMapping("/{reportId}")
    public ModerationReportDto get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID reportId) {
        return moderationService.get(user.accountId(), reportId);
    }

    @PostMapping("/{reportId}/decision")
    public ModerationReportDto decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID reportId,
                                      @Valid @RequestBody ReportDecisionRequest request) {
        return moderationService.decide(user.accountId(), reportId, request);
    }
}
