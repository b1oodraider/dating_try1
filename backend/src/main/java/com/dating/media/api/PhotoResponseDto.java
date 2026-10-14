package com.dating.media.api;

import com.dating.media.domain.ProfilePhoto;

import java.time.Instant;
import java.util.UUID;

public record PhotoResponseDto(
        UUID id,
        UUID profileId,
        String url,
        String mimeType,
        long sizeBytes,
        int sortOrder,
        boolean isPrimary,
        Instant createdAt
) {
    public static PhotoResponseDto from(ProfilePhoto photo) {
        String downloadUrl = "/api/v1/profiles/" + photo.getProfileId() + "/photos/" + photo.getId();
        return new PhotoResponseDto(
                photo.getId(),
                photo.getProfileId(),
                downloadUrl,
                photo.getMimeType(),
                photo.getSizeBytes(),
                photo.getSortOrder(),
                photo.isPrimary(),
                photo.getCreatedAt()
        );
    }
}
