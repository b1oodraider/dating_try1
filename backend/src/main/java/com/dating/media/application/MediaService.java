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

    public MediaService(ProfilePhotoRepository photoRepository,
                        FileStorageService fileStorage,
                        ProfileService profileService,
                        PhotoQueryService photoQuery,
                        PhotoUrlSigner urlSigner,
                        MediaProperties properties,
                        ProductEventPublisher productEvents,
                        ApplicationEventPublisher eventPublisher) {
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
        photoRepository.save(photo);
        productEvents.publish("profile_photo_added", Map.of("photoCount", currentCount + 1));
        eventPublisher.publishEvent(new ProfilePhotoAddedEvent(accountId, photo.getId(), currentCount + 1));
        return photoQuery.toDto(photo);
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

        photo.markDeleted();
        photoRepository.saveAndFlush(photo);
        fileStorage.deleteAfterCommit(List.of(photo.getStorageKey()));

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
