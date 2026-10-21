package com.dating.media.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.media.domain.ProfilePhoto;
import com.dating.media.persistence.ProfilePhotoRepository;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MediaService {

    private final ProfilePhotoRepository photoRepository;
    private final FileStorageService fileStorage;
    private final ProfileService profileService;
    private final PhotoQueryService photoQuery;
    private final PhotoUrlSigner urlSigner;
    private final MediaProperties properties;
    private final ProductEventPublisher productEvents;
    private final ApplicationEventPublisher eventPublisher;
    private final FaceAnalyzer faces;
    private final FaceProperties faceProperties;

    public MediaService(ProfilePhotoRepository photoRepository,
                        FileStorageService fileStorage,
                        ProfileService profileService,
                        PhotoQueryService photoQuery,
                        PhotoUrlSigner urlSigner,
                        MediaProperties properties,
                        ProductEventPublisher productEvents,
                        ApplicationEventPublisher eventPublisher,
                        FaceAnalyzer faces,
                        FaceProperties faceProperties) {
        this.faces = faces;
        this.faceProperties = faceProperties;
        this.eventPublisher = eventPublisher;
        this.photoRepository = photoRepository;
        this.fileStorage = fileStorage;
        this.profileService = profileService;
        this.photoQuery = photoQuery;
        this.urlSigner = urlSigner;
        this.properties = properties;
        this.productEvents = productEvents;
    }

    public record PhotoContent(Path path, String mimeType, long cacheSeconds) {
    }

    @Transactional
    public PhotoDto uploadPhoto(UUID accountId, MultipartFile file) {
        // Row lock on the profile: two parallel uploads cannot both pass the photo limit.
        Profile profile = profileService.lockOrCreateProfile(accountId);

        long currentCount = photoRepository.countByProfileIdAndDeletedAtIsNull(profile.getId());
        if (currentCount >= properties.maxPhotosPerProfile()) {
            throw ApiException.badRequest("PHOTO_LIMIT_EXCEEDED",
                    "Максимум " + properties.maxPhotosPerProfile() + " фотографий в профиле");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Файл пустой");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
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

        FaceAnalyzer.Scan scan = faces.scan(cleaned);
        if (scan != null && !scan.person()) {
            throw ApiException.badRequest("NO_PERSON_ON_PHOTO",
                    "На фото не видно человека. Загрузите фото, где есть вы");
        }
        // A person whose face is hidden (hair, back, far away) is fine once the selfie is verified: the face
        // photos already proved who it is. Before that every photo must show the face to compare with the selfie.
        boolean faceHidden = scan != null && !scan.hasFace();
        if (faceHidden && !profile.isVerified()) {
            throw ApiException.badRequest("NO_FACE_ON_PHOTO", "Пока селфи не подтверждено, на каждом фото должно "
                    + "быть видно лицо. Фото без лица можно добавить после проверки");
        }
        boolean toReview = profile.isVerified() && !faceHidden && !matchesReference(profile.getId(), scan);

        String storageKey = fileStorage.save(cleaned, format.extension());
        ProfilePhoto photo = new ProfilePhoto(
                UUID.randomUUID(),
                profile.getId(),
                storageKey,
                format.mimeType(),
                cleaned.length,
                photoRepository.findMaxSortOrder(profile.getId()) + 1,
                currentCount == 0
        );
        PhotoFingerprint fingerprint = PhotoFingerprint.of(cleaned);
        photo.setFingerprint(fingerprint.sha256(), fingerprint.dhash());
        if (toReview) {
            photo.sendToFaceReview();
            productEvents.publish("photo_sent_to_face_review", Map.of());
        }
        photoRepository.save(photo);
        productEvents.publish("profile_photo_added", Map.of("photoCount", currentCount + 1));
        eventPublisher.publishEvent(new ProfilePhotoAddedEvent(accountId, photo.getId(), currentCount + 1));
        return photoQuery.toDto(photo);
    }

    /**
     * The new face is close enough to a face on one of the reference photos. Unknown (check off, undecodable)
     * or no reference left → false, so a person decides.
     */
    private boolean matchesReference(UUID profileId, FaceAnalyzer.Scan scan) {
        if (scan == null) {
            return false;
        }
        for (ProfilePhoto reference : photoRepository.findAllByProfileIdAndFaceReferenceTrue(profileId)) {
            byte[] bytes = fileStorage.find(reference.getStorageKey()).map(MediaService::readOrNull).orElse(null);
            FaceAnalyzer.Scan known = bytes == null ? null : faces.scan(bytes);
            if (known != null && FaceAnalyzer.similarity(scan, known) >= faceProperties.matchThreshold()) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readOrNull(Path path) {
        try {
            return java.nio.file.Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    /** Selfie approved: the photos in the profile now are what later photos are compared with. */
    @Transactional
    public void markCurrentPhotosAsReference(UUID accountId) {
        profileService.findProfile(accountId).ifPresent(profile ->
                photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profile.getId()).stream()
                        .filter(p -> !p.isUnderFaceReview())
                        .forEach(ProfilePhoto::approveFace));
    }

    @Transactional(readOnly = true)
    public List<PhotoDto> getMyPhotos(UUID accountId) {
        return profileService.findProfile(accountId)
                .map(profile -> photoQuery.photosOf(profile.getId()))
                .orElse(List.of());
    }

    @Transactional
    public void setPrimaryPhoto(UUID accountId, UUID photoId) {
        Profile profile = profileService.lockOrCreateProfile(accountId);
        List<ProfilePhoto> photos = photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profile.getId());
        if (photos.stream().noneMatch(p -> p.getId().equals(photoId))) {
            throw ApiException.notFound("PHOTO_NOT_FOUND", "Фотография не найдена");
        }
        photos.forEach(p -> p.setPrimary(p.getId().equals(photoId)));
    }

    @Transactional
    public void deletePhoto(UUID accountId, UUID photoId) {
        Profile profile = profileService.lockOrCreateProfile(accountId);
        ProfilePhoto photo = photoRepository.findByIdAndProfileIdAndDeletedAtIsNull(photoId, profile.getId())
                .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND", "Фотография не найдена"));

        removePhoto(profile, photo);
    }

    /** Deletes the photo and keeps the profile consistent (a primary photo, a published profile has a photo). */
    void removePhoto(Profile profile, ProfilePhoto photo) {
        photo.markDeleted();
        photoRepository.saveAndFlush(photo);
        // A reference photo keeps its file (never served again: the row is deleted) so later photos can still be
        // compared with it; files go with the account.
        if (!photo.isFaceReference()) {
            fileStorage.deleteAfterCommit(List.of(photo.getStorageKey()));
        }

        List<ProfilePhoto> remaining = photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profile.getId());
        if (!remaining.isEmpty() && remaining.stream().noneMatch(ProfilePhoto::isPrimary)) {
            remaining.get(0).setPrimary(true);
        }
        // A published profile must always have a photo (same rule as on publish).
        if (remaining.isEmpty() && profile.getPublicationStatus() == PublicationStatus.PUBLISHED) {
            profile.setPublicationStatus(PublicationStatus.DRAFT);
        }
        productEvents.publish("profile_photo_removed", Map.of("photoCount", remaining.size()));
    }

    /**
     * Serves a photo by signed URL. Any invalid, expired or tampered link looks exactly like a missing photo.
     */
    @Transactional(readOnly = true)
    public PhotoContent loadPhoto(UUID photoId, long expiresAt, String signature) {
        if (!urlSigner.isValid(photoId, expiresAt, signature)) {
            throw photoNotFound();
        }
        ProfilePhoto photo = photoRepository.findByIdAndDeletedAtIsNull(photoId).orElseThrow(this::photoNotFound);
        Path path = fileStorage.find(photo.getStorageKey()).orElseThrow(this::photoNotFound);
        return new PhotoContent(path, photo.getMimeType(), urlSigner.secondsUntil(expiresAt));
    }

    private ApiException photoNotFound() {
        return ApiException.notFound("PHOTO_NOT_FOUND", "Фотография не найдена");
    }

    /**
     * Runs first among deletion listeners: rows go now, files after the transaction commits.
     */
    @EventListener
    @Order(1)
    public void onAccountDeleted(AccountDeletedEvent event) {
        profileService.findProfile(event.accountId()).ifPresent(profile -> {
            List<ProfilePhoto> photos = photoRepository.findAllByProfileId(profile.getId());
            fileStorage.deleteAfterCommit(photos.stream().map(ProfilePhoto::getStorageKey).toList());
            photoRepository.deleteAll(photos);
        });
    }
}
