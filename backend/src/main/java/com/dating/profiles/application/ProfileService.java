package com.dating.profiles.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.profiles.api.MyProfileDto;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.ProfileUpdateDto;
import com.dating.profiles.api.TagResponseDto;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.persistence.CommunicationTagRepository;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProfileService {

    static final Duration DAILY_STATUS_TTL = Duration.ofHours(24);
    static final int MIN_NAME_LENGTH = 2;
    static final int MAX_NAME_LENGTH = 50;
    static final int MAX_CITY_LENGTH = 60;

    private final ProfileRepository profileRepository;
    private final CommunicationTagRepository tagRepository;
    private final ProfilePhotoQuery photoQuery;
    private final ProductEventPublisher productEvents;
    private final ApplicationEventPublisher eventPublisher;

    public ProfileService(ProfileRepository profileRepository,
                          CommunicationTagRepository tagRepository,
                          ProfilePhotoQuery photoQuery,
                          ProductEventPublisher productEvents,
                          ApplicationEventPublisher eventPublisher) {
        this.profileRepository = profileRepository;
        this.tagRepository = tagRepository;
        this.photoQuery = photoQuery;
        this.productEvents = productEvents;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Profile getOrCreateProfile(UUID accountId) {
        return profileRepository.findByAccountId(accountId).orElseGet(() -> {
            profileRepository.insertIfAbsent(UUID.randomUUID(), accountId);
            return profileRepository.findByAccountId(accountId)
                    .orElseThrow(() -> new IllegalStateException("Profile must exist after insertIfAbsent"));
        });
    }

    /**
     * Same as {@link #getOrCreateProfile} but holds a row lock until the transaction ends.
     */
    @Transactional
    public Profile lockOrCreateProfile(UUID accountId) {
        return profileRepository.findByAccountIdForUpdate(accountId).orElseGet(() -> {
            profileRepository.insertIfAbsent(UUID.randomUUID(), accountId);
            return profileRepository.findByAccountIdForUpdate(accountId)
                    .orElseThrow(() -> new IllegalStateException("Profile must exist after insertIfAbsent"));
        });
    }

    @Transactional(readOnly = true)
    public Optional<Profile> findProfile(UUID accountId) {
        return profileRepository.findByAccountId(accountId);
    }

    @Transactional
    public MyProfileDto getMyProfile(UUID accountId) {
        return toMyProfileDto(getOrCreateProfile(accountId));
    }

    @Transactional
    public MyProfileDto updateProfile(UUID accountId, ProfileUpdateDto dto) {
        Profile profile = lockOrCreateProfile(accountId);
        Map<String, String> errors = new LinkedHashMap<>();

        String displayName = null;
        if (dto.displayName() != null) {
            displayName = dto.displayName().trim();
            if (displayName.length() < MIN_NAME_LENGTH || displayName.length() > MAX_NAME_LENGTH) {
                errors.put("displayName", "Имя должно быть от 2 до 50 символов");
            }
        }
        String city = null;
        if (dto.city() != null) {
            city = dto.city().trim();
            if (city.isEmpty() || city.length() > MAX_CITY_LENGTH) {
                errors.put("city", "Укажите город (до 60 символов)");
            }
        }
        Set<CommunicationTag> tags = null;
        if (dto.tagCodes() != null) {
            tags = resolveTags(dto.tagCodes(), errors);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors.containsKey("tagCodes") ? "UNKNOWN_TAG" : "VALIDATION_ERROR",
                    "Ошибка валидации входных данных", errors);
        }

        if (displayName != null) {
            profile.setDisplayName(displayName);
        }
        if (dto.age() != null) {
            profile.setAge(dto.age());
        }
        if (city != null) {
            profile.setCity(city);
        }
        if (dto.aboutText() != null) {
            String about = dto.aboutText().trim();
            profile.setAboutText(about.isEmpty() ? null : about);
        }
        if (dto.dailyStatusText() != null) {
            String status = dto.dailyStatusText().trim();
            if (status.isEmpty()) {
                profile.setDailyStatus(null, null);
            } else {
                profile.setDailyStatus(status, Instant.now().plus(DAILY_STATUS_TTL));
            }
        }
        if (tags != null) {
            profile.replaceTags(tags);
        }

        return toMyProfileDto(profile);
    }

    private Set<CommunicationTag> resolveTags(List<String> rawCodes, Map<String, String> errors) {
        Set<String> codes = rawCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) {
            return new HashSet<>();
        }
        List<CommunicationTag> found = tagRepository.findAllByCodeInAndActiveTrue(codes);
        if (found.size() != codes.size()) {
            Set<String> foundCodes = found.stream().map(CommunicationTag::getCode).collect(Collectors.toSet());
            List<String> unknown = codes.stream().filter(code -> !foundCodes.contains(code)).toList();
            errors.put("tagCodes", "Неизвестные карточки: " + String.join(", ", unknown));
        }
        return new HashSet<>(found);
    }

    @Transactional
    public MyProfileDto publishProfile(UUID accountId) {
        Profile profile = lockOrCreateProfile(accountId);
        long photoCount = photoQuery.countPhotos(profile.getId());
        Map<String, String> missing = missingForPublish(profile, photoCount);
        if (!missing.isEmpty()) {
            throw ApiException.validation("PROFILE_INCOMPLETE", "Заполните анкету перед публикацией", missing);
        }
        if (profile.getPublicationStatus() != PublicationStatus.PUBLISHED) {
            profile.setPublicationStatus(PublicationStatus.PUBLISHED);
            productEvents.publish("profile_published", Map.of("photoCount", photoCount, "tagCount", profile.getTags().size()));
            eventPublisher.publishEvent(new ProfilePublishedEvent(accountId, profile.getId()));
        }
        return toMyProfileDto(profile);
    }

    @Transactional
    public MyProfileDto hideProfile(UUID accountId) {
        Profile profile = lockOrCreateProfile(accountId);
        if (profile.getPublicationStatus() == PublicationStatus.PUBLISHED) {
            profile.setPublicationStatus(PublicationStatus.HIDDEN);
            productEvents.publish("profile_hidden", Map.of());
        }
        return toMyProfileDto(profile);
    }

    @Transactional(readOnly = true)
    public List<TagResponseDto> listActiveTags() {
        return tagRepository.findAllByActiveTrueOrderBySortOrderAsc()
                .stream()
                .map(TagResponseDto::from)
                .toList();
    }

    MyProfileDto toMyProfileDto(Profile profile) {
        List<PhotoDto> photos = photoQuery.photosOf(profile.getId());
        List<TagResponseDto> tags = profile.getTags().stream()
                .map(TagResponseDto::from)
                .sorted(Comparator.comparingInt(TagResponseDto::sortOrder))
                .toList();
        return new MyProfileDto(
                profile.getId(),
                profile.getDisplayName(),
                profile.getAge(),
                profile.getCity(),
                profile.getAboutText(),
                profile.getPublicationStatus(),
                profile.getDailyStatusText(),
                profile.getDailyStatusExpiresAt(),
                tags,
                photos,
                calculateCompleteness(profile, photos.size()),
                List.copyOf(missingForPublish(profile, photos.size()).keySet()),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    /**
     * Single source of the "ready to publish" rule, used both for the hint and for the publish check.
     */
    public static Map<String, String> missingForPublish(Profile profile, long photoCount) {
        Map<String, String> missing = new LinkedHashMap<>();
        if (isBlank(profile.getDisplayName())) {
            missing.put("displayName", "Укажите имя");
        }
        if (profile.getAge() == null) {
            missing.put("age", "Укажите возраст (18+)");
        }
        if (isBlank(profile.getCity())) {
            missing.put("city", "Укажите город");
        }
        if (photoCount < 1) {
            missing.put("photos", "Добавьте хотя бы одну фотографию");
        }
        return missing;
    }

    public static int calculateCompleteness(Profile profile, long photoCount) {
        int score = 0;
        if (!isBlank(profile.getDisplayName())) score += 20;
        if (profile.getAge() != null) score += 15;
        if (!isBlank(profile.getCity())) score += 15;
        if (profile.getAboutText() != null && profile.getAboutText().trim().length() >= 10) score += 15;
        if (profile.getTags().size() >= 3) score += 15;
        if (photoCount >= 1) score += 20;
        return score;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Runs after media removed the photos (order 1) and before sessions are dropped (order 3).
     */
    @EventListener
    @Order(2)
    public void onAccountDeleted(AccountDeletedEvent event) {
        profileRepository.findByAccountId(event.accountId()).ifPresent(profile -> {
            profile.replaceTags(Set.of());
            profileRepository.delete(profile);
        });
    }
}
