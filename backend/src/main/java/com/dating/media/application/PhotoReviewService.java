package com.dating.media.application;

import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.media.domain.ProfilePhoto;
import com.dating.media.persistence.ProfilePhotoRepository;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderator queue of new photos of verified people whose face did not match their reference photos.
 * Approve: the photo becomes visible and a reference. Reject: the photo is deleted.
 */
@Service
public class PhotoReviewService {

    private static final int MAX_LIMIT = 50;

    /** @param references what the new photo is compared with (by eye) */
    public record Item(UUID photoId, UUID accountId, UUID profileId, String displayName, PhotoDto photo,
                       List<PhotoDto> references, Instant uploadedAt) {
    }

    public record Page(List<Item> items, int page, int limit, long totalElements) {
    }

    private final ProfilePhotoRepository photos;
    private final ProfileRepository profiles;
    private final PhotoQueryService photoQuery;
    private final MediaService mediaService;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;

    public PhotoReviewService(ProfilePhotoRepository photos, ProfileRepository profiles, PhotoQueryService photoQuery,
                              MediaService mediaService, RoleAccess roleAccess, AdminAuditLog auditLog) {
        this.photos = photos;
        this.profiles = profiles;
        this.photoQuery = photoQuery;
        this.mediaService = mediaService;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
    }

    /** Oldest first. */
    @Transactional(readOnly = true)
    public Page queue(UUID actor, int page, int limit) {
        requireModerator(actor);
        int safeLimit = limit <= 0 || limit > MAX_LIMIT ? 20 : limit;
        int safePage = Math.max(page, 0);
        List<Item> items = photos.findAllByFaceReviewAndDeletedAtIsNullOrderByCreatedAtAsc("PENDING",
                        PageRequest.of(safePage, safeLimit)).stream()
                .map(this::item)
                .toList();
        return new Page(items, safePage, safeLimit, photos.countByFaceReviewAndDeletedAtIsNull("PENDING"));
    }

    /**
     * @throws ApiException 404 PHOTO_NOT_FOUND, 409 PHOTO_NOT_UNDER_REVIEW
     */
    @Transactional
    public void decide(UUID actor, UUID photoId, boolean approve) {
        requireModerator(actor);
        ProfilePhoto photo = photos.findByIdAndDeletedAtIsNull(photoId)
                .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND", "Фотография не найдена"));
        if (!photo.isUnderFaceReview()) {
            throw ApiException.conflict("PHOTO_NOT_UNDER_REVIEW", "Это фото уже проверено");
        }
        UUID accountId = profiles.findById(photo.getProfileId()).map(Profile::getAccountId)
                .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND", "Фотография не найдена"));
        Profile profile = profiles.findByAccountIdForUpdate(accountId).orElseThrow(); // as for the owner's own edits
        if (approve) {
            photo.approveFace();
        } else {
            mediaService.removePhoto(profile, photo);
        }
        auditLog.record(actor, approve ? "PHOTO_FACE_APPROVED" : "PHOTO_FACE_REJECTED", "PROFILE_PHOTO",
                photoId.toString(), null, Map.of("accountId", profile.getAccountId().toString()));
    }

    private Item item(ProfilePhoto photo) {
        Profile profile = profiles.findById(photo.getProfileId()).orElse(null);
        List<PhotoDto> references = photos.findAllByProfileIdAndDeletedAtIsNullAndFaceReferenceTrue(photo.getProfileId())
                .stream().map(photoQuery::toDto).toList();
        return new Item(photo.getId(), profile == null ? null : profile.getAccountId(), photo.getProfileId(),
                profile == null ? null : profile.getDisplayName(), photoQuery.toDto(photo), references,
                photo.getCreatedAt());
    }

    private void requireModerator(UUID actor) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
    }
}
