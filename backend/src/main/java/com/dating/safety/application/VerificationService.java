package com.dating.safety.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.profiles.api.PhotoDto;
import com.dating.media.application.FileStorageService;
import com.dating.media.application.ImageFormat;
import com.dating.media.application.ImageMetadataStripper;
import com.dating.media.application.MediaProperties;
import com.dating.media.application.PhotoQueryService;
import com.dating.media.application.ProfilePhotoAddedEvent;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.persistence.ProfileRepository;
import com.dating.safety.domain.Gesture;
import com.dating.safety.domain.Restriction;
import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.persistence.RestrictionRepository;
import com.dating.safety.persistence.VerificationRepository;
import com.dating.safety.persistence.VerificationRepository.Request;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manual selfie verification against fake profiles: the user shows a random gesture, a moderator compares the
 * selfie with the profile photos by eye and approves or rejects. No automated face matching (biometrics, 572-FZ);
 * the selfie is deleted right after the decision. Approval gives the "verified" badge and lifts
 * VERIFICATION_REQUIRED; adding a photo later removes the badge (the new photo was not checked).
 */
@Service
public class VerificationService {

    public record Status(boolean verified, Instant verifiedAt, RequestView current) {
    }

    /** What the user sees about their latest request. */
    public record RequestView(UUID id, String status, String gesture, String instruction, Instant expiresAt,
                              Instant submittedAt, Instant decidedAt, String rejectReason) {
        static RequestView of(Request r) {
            return new RequestView(r.id(), r.status(), r.gesture().name(), r.gesture().instruction(), r.expiresAt(),
                    r.submittedAt(), r.decidedAt(), r.rejectReason());
        }
    }

    /** What the moderator sees: the gesture to look for and the profile photos to compare with. */
    public record ReviewItem(UUID id, UUID accountId, UUID profileId, String displayName, String status,
                             String gesture, String instruction, boolean selfieAvailable, Instant submittedAt,
                             Instant decidedAt, UUID decidedBy, String rejectReason, List<PhotoDto> profilePhotos) {
    }

    public record ReviewPage(List<ReviewItem> items, int page, int limit, long totalElements) {
    }

    public record Selfie(Path path, String mimeType) {
    }

    static final int MINUTES_TO_UPLOAD = 30;
    static final int MAX_REQUESTS_PER_DAY = 3;
    private static final int MAX_LIMIT = 100;

    private final VerificationRepository repository;
    private final RestrictionRepository restrictions;
    private final SanctionService sanctionService;
    private final FileStorageService fileStorage;
    private final MediaProperties mediaProperties;
    private final PhotoQueryService photoQuery;
    private final ProfileRepository profileRepository;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;

    public VerificationService(VerificationRepository repository, RestrictionRepository restrictions,
                               SanctionService sanctionService, FileStorageService fileStorage,
                               MediaProperties mediaProperties, PhotoQueryService photoQuery,
                               ProfileRepository profileRepository, RoleAccess roleAccess, AdminAuditLog auditLog) {
        this.repository = repository;
        this.restrictions = restrictions;
        this.sanctionService = sanctionService;
        this.fileStorage = fileStorage;
        this.mediaProperties = mediaProperties;
        this.photoQuery = photoQuery;
        this.profileRepository = profileRepository;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
    }

    // ---------------------------------------------------------------- user

    @Transactional
    public Status status(UUID accountId) {
        repository.expireStale(accountId);
        Instant verifiedAt = repository.verifiedAt(accountId).orElse(null);
        return new Status(verifiedAt != null, verifiedAt, repository.latest(accountId).map(RequestView::of).orElse(null));
    }

    /**
     * Returns the open request (with its gesture) or creates one.
     *
     * @throws ApiException 409 ALREADY_VERIFIED | VERIFICATION_PENDING, 429 RATE_LIMITED (3 requests a day)
     */
    @Transactional
    public RequestView start(UUID accountId) {
        repository.lockAccount(accountId);
        repository.expireStale(accountId);
        if (repository.verifiedAt(accountId).isPresent()) {
            throw ApiException.conflict("ALREADY_VERIFIED", "Профиль уже подтверждён");
        }
        var latest = repository.latest(accountId);
        if (latest.isPresent() && latest.get().status().equals("PENDING")) {
            throw ApiException.conflict("VERIFICATION_PENDING", "Селфи уже на проверке");
        }
        if (latest.isPresent() && latest.get().status().equals("AWAITING_SELFIE")) {
            return RequestView.of(latest.get());
        }
        if (repository.countSince(accountId, Instant.now().minus(Duration.ofDays(1))) >= MAX_REQUESTS_PER_DAY) {
            throw ApiException.tooManyRequests("Слишком много попыток подтверждения. Попробуйте завтра",
                    Duration.ofHours(24).toSeconds());
        }
        return RequestView.of(repository.create(accountId, Gesture.random(), MINUTES_TO_UPLOAD));
    }

    /**
     * @throws ApiException 404 VERIFICATION_NOT_FOUND, 409 VERIFICATION_EXPIRED | VERIFICATION_NOT_AWAITING,
     *                      400 EMPTY_FILE, 413 FILE_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE
     */
    @Transactional
    public RequestView submitSelfie(UUID accountId, UUID requestId, MultipartFile file) {
        repository.lockAccount(accountId);
        Request request = repository.findForUpdate(requestId)
                .filter(r -> r.accountId().equals(accountId))
                .orElseThrow(() -> ApiException.notFound("VERIFICATION_NOT_FOUND", "Запрос подтверждения не найден"));
        if (!request.status().equals("AWAITING_SELFIE")) {
            throw ApiException.conflict("VERIFICATION_NOT_AWAITING", "Селфи для этого запроса уже не принимается");
        }
        if (!request.expiresAt().isAfter(Instant.now())) {
            repository.expireStale(accountId);
            throw ApiException.conflict("VERIFICATION_EXPIRED", "Время на селфи истекло. Начните заново");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Файл пустой");
        }
        if (file.getSize() > mediaProperties.maxFileSize().toBytes()) {
            throw ApiException.payloadTooLarge("FILE_TOO_LARGE", "Размер файла превышает 5 МБ");
        }
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
        ImageFormat format = ImageFormat.detect(original).orElseThrow(() ->
                ApiException.unsupportedMediaType("Поддерживаются только изображения JPEG, PNG и WebP"));
        byte[] cleaned;
        try {
            cleaned = ImageMetadataStripper.strip(original, format);
        } catch (IllegalArgumentException e) {
            throw ApiException.unsupportedMediaType("Файл повреждён или не является изображением");
        }
        repository.submit(requestId, fileStorage.save(cleaned, format.extension()), format.mimeType());
        return RequestView.of(repository.find(requestId).orElseThrow());
    }

    // ---------------------------------------------------------------- moderators

    /** @param status null = PENDING (the review queue) */
    @Transactional(readOnly = true)
    public ReviewPage queue(UUID actor, String status, int page, int limit) {
        requireModerator(actor);
        String wanted = status == null ? "PENDING" : status.toUpperCase();
        if (!List.of("AWAITING_SELFIE", "PENDING", "APPROVED", "REJECTED", "EXPIRED").contains(wanted)) {
            throw ApiException.validation("VALIDATION_ERROR", "Неизвестный статус",
                    Map.of("status", "Одно из: AWAITING_SELFIE, PENDING, APPROVED, REJECTED, EXPIRED"));
        }
        int safeLimit = limit <= 0 || limit > MAX_LIMIT ? 20 : limit;
        int safePage = Math.max(page, 0);
        List<ReviewItem> items = repository.page(List.of(wanted), safeLimit, safePage * safeLimit).stream()
                .map(this::reviewItem).toList();
        return new ReviewPage(items, safePage, safeLimit, repository.count(List.of(wanted)));
    }

    @Transactional(readOnly = true)
    public ReviewItem get(UUID actor, UUID requestId) {
        requireModerator(actor);
        return reviewItem(require(requestId));
    }

    /**
     * @throws ApiException 404 VERIFICATION_NOT_FOUND | SELFIE_NOT_FOUND (already decided and deleted)
     */
    @Transactional(readOnly = true)
    public Selfie selfie(UUID actor, UUID requestId) {
        requireModerator(actor);
        Request request = require(requestId);
        Path path = request.selfieKey() == null ? null : fileStorage.find(request.selfieKey()).orElse(null);
        if (path == null || !Files.exists(path)) {
            throw ApiException.notFound("SELFIE_NOT_FOUND", "Селфи нет: решение уже принято или файл не загружен");
        }
        return new Selfie(path, request.selfieMime());
    }

    /**
     * @throws ApiException 404 VERIFICATION_NOT_FOUND, 409 VERIFICATION_NOT_PENDING,
     *                      400 VALIDATION_ERROR (a rejection needs a reason the user will see)
     */
    @Transactional
    public ReviewItem decide(UUID actor, UUID requestId, boolean approve, String reason) {
        requireModerator(actor);
        Request request = repository.findForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("VERIFICATION_NOT_FOUND", "Запрос подтверждения не найден"));
        if (!request.status().equals("PENDING")) {
            throw ApiException.conflict("VERIFICATION_NOT_PENDING", "Этот запрос не ждёт решения");
        }
        String note = reason == null || reason.isBlank() ? null : reason.trim();
        if (!approve && note == null) {
            throw ApiException.validation("VALIDATION_ERROR", "Укажите причину отказа",
                    Map.of("reason", "Пользователь увидит её, чтобы переснять селфи"));
        }
        ReviewItem before = reviewItem(request);
        repository.decide(requestId, approve ? "APPROVED" : "REJECTED", actor, approve ? null : note);
        fileStorage.deleteAfterCommit(List.of(request.selfieKey()));
        if (approve) {
            repository.setVerified(request.accountId(), true);
            for (Restriction r : restrictions.active(request.accountId())) {
                if (r.kind() == RestrictionKind.VERIFICATION_REQUIRED) {
                    sanctionService.lift(r.id(), actor, "selfie verified");
                }
            }
        }
        ReviewItem after = reviewItem(repository.find(requestId).orElseThrow());
        auditLog.record(actor, approve ? "APPROVE" : "REJECT", "VERIFICATION", requestId.toString(), before, after);
        return after;
    }

    // ---------------------------------------------------------------- lifecycle

    /** A new photo was not compared with the selfie: the badge must be earned again. */
    @EventListener
    public void onPhotoAdded(ProfilePhotoAddedEvent event) {
        repository.setVerified(event.accountId(), false);
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        fileStorage.deleteAfterCommit(repository.deleteAccountData(event.accountId()));
    }

    private ReviewItem reviewItem(Request r) {
        Profile profile = profileRepository.findByAccountId(r.accountId()).orElse(null);
        return new ReviewItem(r.id(), r.accountId(), profile == null ? null : profile.getId(),
                profile == null ? null : profile.getDisplayName(), r.status(), r.gesture().name(),
                r.gesture().instruction(), r.selfieKey() != null, r.submittedAt(), r.decidedAt(), r.decidedBy(),
                r.rejectReason(), profile == null ? List.of() : photoQuery.photosOf(profile.getId()));
    }

    private Request require(UUID requestId) {
        return repository.find(requestId)
                .orElseThrow(() -> ApiException.notFound("VERIFICATION_NOT_FOUND", "Запрос подтверждения не найден"));
    }

    private void requireModerator(UUID actor) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
    }
}
