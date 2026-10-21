package com.dating.profiles.api;

import com.dating.profiles.domain.Gender;

import java.time.LocalDate;

import com.dating.profiles.application.ProfileTips;
import com.dating.profiles.domain.PublicationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's view of their profile.
 *
 * @param missingForPublish fields still required before publishing; empty means the profile can be published
 * @param decorations       equipped public cosmetics, i.e. how others see the profile
 * @param ageFrom           youngest age I accept; ageTo null = no upper limit (both hard filters, both ways)
 */
public record MyProfileDto(
        UUID id,
        String displayName,
        Integer age,
        String city,
        String aboutText,
        PublicationStatus publicationStatus,
        String dailyStatusText,
        Instant dailyStatusExpiresAt,
        List<TagResponseDto> tags,
        List<PhotoDto> photos,
        int completenessPercent,
        List<ProfileTips.Tip> tips,
        List<String> missingForPublish,
        Map<String, DecorationDto> decorations,
        Integer heightCm,
        List<GoalDto> goals,
        boolean premium,
        boolean verified,
        Gender gender,
        Gender seeking,
        LocalDate birthDate,
        int ageFrom,
        Integer ageTo,
        Integer cityId,
        Integer searchRadiusKm,
        boolean locationShared,
        /** Gender and birth date can only be changed via support (published, not a test account). */
        boolean identityLocked,
        boolean invisible,
        boolean showPremiumBadge,
        List<InterestDto> interests,
        List<ArtistDto> artists,
        boolean showZodiac,
        /** My sign, always (shown to others only with showZodiac). */
        String zodiac,
        FactsDto facts,
        Instant createdAt,
        Instant updatedAt
) {}
