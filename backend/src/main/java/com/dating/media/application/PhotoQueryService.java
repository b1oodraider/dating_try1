package com.dating.media.application;

import com.dating.media.domain.ProfilePhoto;
import com.dating.media.persistence.ProfilePhotoRepository;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.application.ProfilePhotoQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PhotoQueryService implements ProfilePhotoQuery {

    private final ProfilePhotoRepository photoRepository;
    private final PhotoUrlSigner urlSigner;

    public PhotoQueryService(ProfilePhotoRepository photoRepository, PhotoUrlSigner urlSigner) {
        this.photoRepository = photoRepository;
        this.urlSigner = urlSigner;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PhotoDto> photosOf(UUID profileId) {
        return photoRepository.findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(profileId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PhotoDto>> photosOf(Collection<UUID> profileIds) {
        if (profileIds.isEmpty()) {
            return Map.of();
        }
        return photoRepository.findAllByProfileIdInAndDeletedAtIsNullOrderBySortOrderAsc(profileIds)
                .stream()
                .collect(Collectors.groupingBy(ProfilePhoto::getProfileId,
                        Collectors.mapping(this::toDto, Collectors.toList())));
    }

    @Override
    @Transactional(readOnly = true)
    public long countPhotos(UUID profileId) {
        return photoRepository.countByProfileIdAndDeletedAtIsNull(profileId);
    }

    public PhotoDto toDto(ProfilePhoto photo) {
        return new PhotoDto(
                photo.getId(),
                urlSigner.signedUrl(photo.getId()),
                photo.getMimeType(),
                photo.getSizeBytes(),
                photo.getSortOrder(),
                photo.isPrimary(),
                photo.getCreatedAt()
        );
    }
}
