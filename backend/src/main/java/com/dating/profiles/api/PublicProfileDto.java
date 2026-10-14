package com.dating.profiles.api;

import com.dating.profiles.domain.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What other users see. Deliberately has no accountId or internal statuses.
 */
public record PublicProfileDto(
        UUID id,
        String displayName,
        Integer age,
        String city,
        String aboutText,
        String dailyStatusText,
        List<TagResponseDto> tags,
        List<PhotoDto> photos
) {
    public static PublicProfileDto from(Profile profile, List<PhotoDto> photos) {
        List<TagResponseDto> tags = profile.getTags().stream()
                .map(TagResponseDto::from)
                .sorted(Comparator.comparingInt(TagResponseDto::sortOrder))
                .toList();
        return new PublicProfileDto(
                profile.getId(),
                profile.getDisplayName(),
                profile.getAge(),
                profile.getCity(),
                profile.getAboutText(),
                profile.getDailyStatusText(),
                tags,
                photos
        );
    }
}
