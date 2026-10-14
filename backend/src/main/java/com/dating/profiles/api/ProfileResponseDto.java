package com.dating.profiles.api;

import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;

import java.util.List;
import java.util.UUID;

public record ProfileResponseDto(
        UUID id,
        UUID accountId,
        String displayName,
        int age,
        String city,
        String aboutText,
        PublicationStatus publicationStatus,
        VisibilityStatus visibilityStatus,
        String dailyStatusText,
        List<TagResponseDto> tags,
        int completenessPercent
) {
    public static ProfileResponseDto from(Profile profile, int completenessPercent) {
        List<TagResponseDto> tagDtos = profile.getTags().stream()
                .map(TagResponseDto::from)
                .toList();

        return new ProfileResponseDto(
                profile.getId(),
                profile.getAccountId(),
                profile.getDisplayName(),
                profile.getAge(),
                profile.getCity(),
                profile.getAboutText(),
                profile.getPublicationStatus(),
                profile.getVisibilityStatus(),
                profile.getDailyStatusText(),
                tagDtos,
                completenessPercent
        );
    }
}
