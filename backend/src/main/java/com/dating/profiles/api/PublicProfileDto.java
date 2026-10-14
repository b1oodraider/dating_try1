package com.dating.profiles.api;

import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What other users see. Deliberately has no accountId or internal statuses.
 *
 * @param decorations equipped public cosmetics by slot (PHOTO_FRAME, PROFILE_STATUS, …)
 * @param premium     owner has premium right now (premium badge)
 */
public record PublicProfileDto(
        UUID id,
        String displayName,
        Integer age,
        String city,
        String aboutText,
        String dailyStatusText,
        List<TagResponseDto> tags,
        List<PhotoDto> photos,
        Map<String, DecorationDto> decorations,
        Integer heightCm,
        List<GoalDto> goals,
        boolean premium
) {
    public static PublicProfileDto from(Profile profile, List<PhotoDto> photos, Map<String, DecorationDto> decorations,
                                        boolean premium) {
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
                photos,
                decorations,
                profile.getHeightCm(),
                ProfileService.goalsOf(profile),
                premium
        );
    }
}
