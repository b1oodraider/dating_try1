package com.dating.profiles.application;

import com.dating.common.errors.ApiException;
import com.dating.profiles.api.ProfileResponseDto;
import com.dating.profiles.api.ProfileUpdateDto;
import com.dating.profiles.api.TagResponseDto;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.persistence.CommunicationTagRepository;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final CommunicationTagRepository tagRepository;

    public ProfileService(ProfileRepository profileRepository, CommunicationTagRepository tagRepository) {
        this.profileRepository = profileRepository;
        this.tagRepository = tagRepository;
    }

    @Transactional
    public Profile getOrCreateProfile(UUID accountId) {
        return profileRepository.findByAccountId(accountId)
                .orElseGet(() -> {
                    Profile profile = new Profile(UUID.randomUUID(), accountId, "Новый пользователь", 18, "Москва");
                    return profileRepository.save(profile);
                });
    }

    @Transactional(readOnly = true)
    public ProfileResponseDto getMyProfileDto(UUID accountId) {
        Profile profile = getOrCreateProfile(accountId);
        int completeness = calculateCompleteness(profile);
        return ProfileResponseDto.from(profile, completeness);
    }

    @Transactional
    public ProfileResponseDto updateProfile(UUID accountId, ProfileUpdateDto dto) {
        Profile profile = getOrCreateProfile(accountId);

        if (dto.displayName() != null) {
            profile.setDisplayName(dto.displayName().trim());
        }
        if (dto.age() != null) {
            profile.setAge(dto.age());
        }
        if (dto.city() != null) {
            profile.setCity(dto.city().trim());
        }
        if (dto.aboutText() != null) {
            profile.setAboutText(dto.aboutText().trim());
        }
        if (dto.dailyStatusText() != null) {
            if (dto.dailyStatusText().isBlank()) {
                profile.setDailyStatus(null, null);
            } else {
                Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
                profile.setDailyStatus(dto.dailyStatusText().trim(), expiresAt);
            }
        }
        if (dto.tagCodes() != null) {
            Set<CommunicationTag> tags = tagRepository.findAllByCodeIn(dto.tagCodes());
            profile.setTags(tags);
        }

        Profile saved = profileRepository.save(profile);
        return ProfileResponseDto.from(saved, calculateCompleteness(saved));
    }

    @Transactional
    public ProfileResponseDto publishProfile(UUID accountId) {
        Profile profile = getOrCreateProfile(accountId);
        profile.setPublicationStatus(PublicationStatus.PUBLISHED);
        Profile saved = profileRepository.save(profile);
        return ProfileResponseDto.from(saved, calculateCompleteness(saved));
    }

    @Transactional
    public ProfileResponseDto hideProfile(UUID accountId) {
        Profile profile = getOrCreateProfile(accountId);
        profile.setPublicationStatus(PublicationStatus.HIDDEN);
        Profile saved = profileRepository.save(profile);
        return ProfileResponseDto.from(saved, calculateCompleteness(saved));
    }

    @Transactional(readOnly = true)
    public List<TagResponseDto> listActiveTags() {
        return tagRepository.findAllByActiveTrueOrderBySortOrderAsc()
                .stream()
                .map(TagResponseDto::from)
                .toList();
    }

    public int calculateCompleteness(Profile profile) {
        int score = 0;
        if (profile.getDisplayName() != null && profile.getDisplayName().trim().length() > 1) score += 20;
        if (profile.getAge() >= 18) score += 15;
        if (profile.getCity() != null && !profile.getCity().trim().isEmpty()) score += 15;
        if (profile.getAboutText() != null && profile.getAboutText().trim().length() >= 10) score += 25;
        if (profile.getTags() != null && profile.getTags().size() >= 3) score += 25;
        return Math.min(100, score);
    }
}
