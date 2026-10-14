package com.dating.media.application;

import com.dating.common.errors.ApiException;
import com.dating.media.api.PhotoResponseDto;
import com.dating.media.domain.ProfilePhoto;
import com.dating.media.persistence.ProfilePhotoRepository;
import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    private final ProfilePhotoRepository photoRepository;
    private final FileStorageService fileStorageService;
    private final ProfileService profileService;

    public MediaService(ProfilePhotoRepository photoRepository,
                        FileStorageService fileStorageService,
                        ProfileService profileService) {
        this.photoRepository = photoRepository;
        this.fileStorageService = fileStorageService;
        this.profileService = profileService;
    }

    @Transactional
    public PhotoResponseDto uploadPhoto(UUID accountId, MultipartFile file) {
        Profile profile = profileService.getOrCreateProfile(accountId);

        long currentCount = photoRepository.countByProfileIdAndDeletedAtIsNull(profile.getId());
        if (currentCount >= 6) {
            throw ApiException.badRequest("PHOTO_LIMIT_EXCEEDED", "Максимум 6 фотографий в профиле");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Размер файла превышает 5 МБ");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw ApiException.badRequest("UNSUPPORTED_MEDIA_TYPE", "Поддерживаются только форматы JPEG, PNG и WebP");
        }

        String extension = switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };

        try {
            String storageKey = fileStorageService.saveFile(file.getInputStream(), extension);
            boolean isPrimary = currentCount == 0;

            ProfilePhoto photo = new ProfilePhoto(
                    UUID.randomUUID(),
                    profile.getId(),
                    storageKey,
                    contentType,
                    file.getSize(),
                    (int) currentCount,
                    isPrimary
            );

            ProfilePhoto saved = photoRepository.save(photo);
            return PhotoResponseDto.from(saved);
        } catch (IOException e) {
            throw new RuntimeException("Could not process uploaded file", e);
        }
    }

    @Transactional(readOnly = true)
    public List<PhotoResponseDto> getMyPhotos(UUID accountId) {
        Profile profile = profileService.getOrCreateProfile(accountId);
        return photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profile.getId())
                .stream()
                .map(PhotoResponseDto::from)
                .toList();
    }

    @Transactional
    public void setPrimaryPhoto(UUID accountId, UUID photoId) {
        Profile profile = profileService.getOrCreateProfile(accountId);
        List<ProfilePhoto> photos = photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profile.getId());

        boolean found = false;
        for (ProfilePhoto photo : photos) {
            if (photo.getId().equals(photoId)) {
                photo.setPrimary(true);
                found = true;
            } else {
                photo.setPrimary(false);
            }
        }

        if (!found) {
            throw ApiException.notFound("Фотография не найдена");
        }

        photoRepository.saveAll(photos);
    }

    @Transactional
    public void deletePhoto(UUID accountId, UUID photoId) {
        Profile profile = profileService.getOrCreateProfile(accountId);
        ProfilePhoto photo = photoRepository.findByIdAndProfileIdAndDeletedAtIsNull(photoId, profile.getId())
                .orElseThrow(() -> ApiException.notFound("Фотография не найдена"));

        photo.markDeleted();
        photoRepository.save(photo);
        fileStorageService.deleteFile(photo.getStorageKey());

        // Reassign primary if needed
        List<ProfilePhoto> remaining = photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profile.getId());
        if (!remaining.isEmpty() && remaining.stream().noneMatch(ProfilePhoto::isPrimary)) {
            remaining.get(0).setPrimary(true);
            photoRepository.save(remaining.get(0));
        }
    }

    @Transactional(readOnly = true)
    public byte[] downloadPhoto(UUID profileId, UUID photoId) {
        ProfilePhoto photo = photoRepository.findByIdAndProfileIdAndDeletedAtIsNull(photoId, profileId)
                .orElseThrow(() -> ApiException.notFound("Фотография не найдена"));

        return fileStorageService.loadFile(photo.getStorageKey());
    }
}
