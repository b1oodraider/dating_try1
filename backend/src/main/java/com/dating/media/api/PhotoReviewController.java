package com.dating.media.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.media.application.PhotoReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Moderators: new photos of verified people whose face did not match (MODERATOR / ADMIN). */
@RestController
public class PhotoReviewController {

    public record DecisionRequest(@NotNull Boolean approve) {
    }

    private final PhotoReviewService service;

    public PhotoReviewController(PhotoReviewService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/photo-reviews")
    public PhotoReviewService.Page queue(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int limit) {
        return service.queue(user.accountId(), page, limit);
    }

    @PostMapping("/api/v1/admin/photo-reviews/{photoId}/decision")
    public ResponseEntity<Void> decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID photoId,
                                       @Valid @RequestBody DecisionRequest request) {
        service.decide(user.accountId(), photoId, request.approve());
        return ResponseEntity.noContent().build();
    }
}
