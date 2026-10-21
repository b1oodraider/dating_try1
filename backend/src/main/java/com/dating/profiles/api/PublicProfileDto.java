package com.dating.profiles.api;

import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.CommunicationTag;
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
        boolean verified,
        Integer compatibility,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean boosted,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer distanceKm
) {
    /** @param distanceKm shown only when both people share their location (city centres would mislead) */
    public PublicProfileDto withDistanceKm(Integer distanceKm) {
        return new PublicProfileDto(id, displayName, age, city, aboutText, dailyStatusText, tags, photos, decorations,
                heightCm, goals, premium, verified, compatibility, boosted, distanceKm);
    }

    /** @param compatibility percent match by cards with the viewer, null when there is too little to compare */
    public static PublicProfileDto from(Profile profile, List<PhotoDto> photos, Map<String, DecorationDto> decorations,
                                        boolean premium, Integer compatibility, Boolean boosted) {
        List<TagResponseDto> tags = profile.getTags().stream()
                .filter(CommunicationTag::isActive) // hidden by an admin
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
                premium && profile.isShowPremiumBadge(),
                profile.isVerified(),
                compatibility,
                boosted,
                null
        );
    }
}
