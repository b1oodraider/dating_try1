package com.dating.safety.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.safety.application.VerificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Selfie verification: the user's side (/me/verification) and the moderators' review queue (/admin/verifications).
 */
@RestController
@RequestMapping("/api/v1")
public class VerificationController {

    /** @param faceComparisonConsent consent to comparing my photos with the selfie and with each other (152-ФЗ) */
    public record StartRequest(Boolean faceComparisonConsent) {
    }

    public record DecisionRequest(
            @NotNull(message = "Укажите решение") Boolean approve,
            @Size(max = 500, message = "Причина не должна превышать 500 символов") String reason
    ) {
    }

    private final VerificationService service;

    public VerificationController(VerificationService service) {
        this.service = service;
    }

    @GetMapping("/me/verification")
    public VerificationService.Status status(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.status(user.accountId());
    }

    @PostMapping("/me/verification")
    public VerificationService.RequestView start(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestBody(required = false) StartRequest request) {
        return service.start(user.accountId(), request != null && Boolean.TRUE.equals(request.faceComparisonConsent()));
    }

    @PostMapping(value = "/me/verification/{requestId}/selfie", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VerificationService.RequestView selfie(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable UUID requestId,
                                                  @RequestParam("file") MultipartFile file) {
        return service.submitSelfie(user.accountId(), requestId, file);
    }

    @GetMapping("/admin/verifications")
    public VerificationService.ReviewPage queue(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int limit) {
        return service.queue(user.accountId(), status, page, limit);
    }

    @GetMapping("/admin/verifications/{requestId}")
    public VerificationService.ReviewItem get(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable UUID requestId) {
        return service.get(user.accountId(), requestId);
    }

    /** The selfie itself; needs the Authorization header (never a public or signed URL). */
    @GetMapping("/admin/verifications/{requestId}/selfie")
    public ResponseEntity<Resource> selfieImage(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable UUID requestId) {
        VerificationService.Selfie selfie = service.selfie(user.accountId(), requestId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(selfie.mimeType()))
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(selfie.path()));
    }

    @PostMapping("/admin/verifications/{requestId}/decision")
    public VerificationService.ReviewItem decide(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable UUID requestId,
                                                 @Valid @RequestBody DecisionRequest request) {
        return service.decide(user.accountId(), requestId, request.approve(), request.reason());
    }
}
