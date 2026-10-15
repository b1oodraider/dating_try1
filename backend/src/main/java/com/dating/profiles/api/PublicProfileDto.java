package com.dating.profiles.api;

import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.Profile;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What other users see. Deliberately has no accountId or internal statuses.
 *
 * @param decorations equipped public cosmetics by slot (PHOTO_FRAME, PROFILE_STATUS, …)
 * @param premium     owner has premium right now (premium badge)
 * @param boosted     true while the profile is boosted, only when app.boost.public-badge=true; otherwise omitted
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
        boolean premium,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean boosted
) {
    public static PublicProfileDto from(Profile profile, List<PhotoDto> photos, Map<String, DecorationDto> decorations,
                                        boolean premium) {
        return from(profile, photos, decorations, premium, null);
    }

    public static PublicProfileDto from(Profile profile, List<PhotoDto> photos, Map<String, DecorationDto> decorations,
                                        boolean premium, Boolean boosted) {
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
                premium,
                boosted
        );
    }
}
