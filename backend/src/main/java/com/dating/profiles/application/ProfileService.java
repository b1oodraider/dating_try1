package com.dating.profiles.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.entitlements.PremiumFeatures;
import com.dating.profiles.api.GoalDto;
import com.dating.profiles.api.MyProfileDto;
import com.dating.profiles.domain.RelationshipGoal;
import com.dating.profiles.persistence.RelationshipGoalRepository;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.ProfileUpdateDto;
import com.dating.profiles.api.TagResponseDto;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.persistence.CityRepository;
import com.dating.profiles.persistence.CommunicationTagRepository;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProfileService {

    static final Duration DAILY_STATUS_TTL = Duration.ofHours(24);
    static final int MIN_NAME_LENGTH = 2;
    static final int MAX_NAME_LENGTH = 50;
    static final int MAX_CITY_LENGTH = 60;
    static final int MIN_AGE = 18;
    static final int MAX_AGE = 100;

    private final ProfileRepository profileRepository;
    private final CommunicationTagRepository tagRepository;
    private final ProfilePhotoQuery photoQuery;
    private final ProductEventPublisher productEvents;
    private final ApplicationEventPublisher eventPublisher;
    private final ProfileDecorationQuery decorationQuery;
    private final RelationshipGoalRepository goalRepository;
    private final FeatureAccess featureAccess;
    private final com.dating.accounts.application.AccountService accounts;
    private final CityRepository cityRepository;

    public ProfileService(ProfileRepository profileRepository,
                          CommunicationTagRepository tagRepository,
                          ProfilePhotoQuery photoQuery,
                          ProductEventPublisher productEvents,
                          ApplicationEventPublisher eventPublisher,
                          ProfileDecorationQuery decorationQuery,
                          RelationshipGoalRepository goalRepository,
                          FeatureAccess featureAccess,
                          CityRepository cityRepository,
                          com.dating.accounts.application.AccountService accounts) {
        this.accounts = accounts;
        this.cityRepository = cityRepository;
        this.profileRepository = profileRepository;
        this.tagRepository = tagRepository;
        this.photoQuery = photoQuery;
        this.productEvents = productEvents;
        this.eventPublisher = eventPublisher;
        this.decorationQuery = decorationQuery;
        this.goalRepository = goalRepository;
        this.featureAccess = featureAccess;
    }

    @Transactional
    public Profile getOrCreateProfile(UUID accountId) {
        return profileRepository.findByAccountId(accountId).orElseGet(() -> {
            profileRepository.insertIfAbsent(UUID.randomUUID(), accountId);
            return profileRepository.findByAccountId(accountId)
                    .orElseThrow(() -> new IllegalStateException("Profile must exist after insertIfAbsent"));
        });
    }

    /** Moderation removes offending texts; the owner can write new ones. */
    @Transactional
    public void clearTextsByModeration(UUID accountId, boolean aboutText, boolean dailyStatus) {
        Profile profile = lockOrCreateProfile(accountId);
        if (aboutText) {
            profile.setAboutText(null);
        }
        if (dailyStatus) {
            profile.setDailyStatus(null, null);
        }
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
        CityRepository.City dictionaryCity = null;
        if (dto.cityId() != null) {
            dictionaryCity = cityRepository.findById(dto.cityId()).orElse(null);
            if (dictionaryCity == null) {
                errors.put("cityId", "Выберите город из списка");
            }
        } else if (dto.city() != null) {
            city = dto.city().trim();
            if (city.isEmpty() || city.length() > MAX_CITY_LENGTH) {
                errors.put("city", "Укажите город (до 60 символов)");
            } else {
                // Older clients send text: «Питер» and «Санкт-Петербург» become the same city.
                dictionaryCity = cityRepository.resolve(city).orElse(null);
            }
        }
        if (Boolean.TRUE.equals(dto.clearSearchRadius()) && dto.searchRadiusKm() != null) {
            errors.put("searchRadiusKm", "Нельзя одновременно указать и убрать радиус");
        }
        Set<CommunicationTag> tags = null;
        if (dto.tagCodes() != null) {
            tags = resolveTags(dto.tagCodes(), errors);
        }
        Set<RelationshipGoal> goals = null;
        if (dto.goalCodes() != null) {
            goals = resolveGoals(dto.goalCodes(), errors);
        }
        boolean clearHeight = Boolean.TRUE.equals(dto.clearHeight());
        if (clearHeight && dto.heightCm() != null) {
            errors.put("heightCm", "Нельзя одновременно указать и удалить рост");
        }
        // shortcut: legacy clients send a plain age; it becomes a mid-year birth date. Drop once clients send birthDate.
        LocalDate birthDate = dto.birthDate() != null ? dto.birthDate()
                : dto.age() != null ? LocalDate.now().minusYears(dto.age()).minusDays(183) : null;
        if (birthDate != null) {
            int years = Period.between(birthDate, LocalDate.now()).getYears();
            if (years < MIN_AGE || years > MAX_AGE) {
                errors.put("birthDate", "Возраст должен быть от 18 до 100 лет");
            }
        }
        boolean identityLocked = identityLocked(profile);
        if (identityLocked && dto.gender() != null && profile.getGender() != null && dto.gender() != profile.getGender()) {
            errors.put("gender", "Пол после публикации меняется только через поддержку");
        }
        // After publishing the exact date may still be refined (dates migrated from a plain age are approximate),
        // but not to a different age.
        if (identityLocked && dto.birthDate() != null && profile.getBirthDate() != null
                && !Objects.equals(Period.between(dto.birthDate(), LocalDate.now()).getYears(), profile.getAge())) {
            errors.put("birthDate", "Возраст после публикации можно изменить только через поддержку");
        }
        boolean clearAgeTo = Boolean.TRUE.equals(dto.clearAgeTo());
        if (clearAgeTo && dto.ageTo() != null) {
            errors.put("ageTo", "Нельзя одновременно указать и убрать верхнюю границу возраста");
        }
        int ageFrom = dto.ageFrom() != null ? dto.ageFrom() : profile.getPrefAgeMin();
        Integer ageTo = clearAgeTo ? null : dto.ageTo() != null ? dto.ageTo() : profile.getPrefAgeMax();
        if (ageTo != null && ageTo < ageFrom) {
            errors.put("ageTo", "Верхняя граница возраста меньше нижней");
        }
        if (!errors.isEmpty()) {
            String code = errors.containsKey("tagCodes") ? "UNKNOWN_TAG"
                    : errors.containsKey("goalCodes") ? "UNKNOWN_GOAL" : "VALIDATION_ERROR";
            throw ApiException.validation(code, "Ошибка валидации входных данных", errors);
        }
        if (clearHeight) {
            profile.setHeightCm(null);
        } else if (dto.heightCm() != null) {
            profile.setHeightCm(dto.heightCm());
        }
        if (goals != null) {
            profile.replaceGoals(goals);
        }

        if (displayName != null) {
            profile.setDisplayName(displayName);
        }
        if (birthDate != null && (dto.birthDate() != null || !identityLocked || profile.getBirthDate() == null)) {
            profile.setBirthDate(birthDate);
        }
        if (dto.gender() != null) {
            profile.setGender(dto.gender());
        }
        if (dto.seeking() != null) {
            profile.setSeeking(dto.seeking());
        }
        if (dto.ageFrom() != null || dto.ageTo() != null || clearAgeTo) {
            profile.setPrefAgeRange(ageFrom, ageTo);
        }
        if (dictionaryCity != null) {
            profile.setCity(dictionaryCity.id(), dictionaryCity.name());
        } else if (city != null) {
            profile.setCity(city); // shortcut: unknown free-text city keeps no id (no distance); drop when clients send cityId
        }
        if (Boolean.TRUE.equals(dto.clearSearchRadius())) {
            profile.setSearchRadiusKm(null);
        } else if (dto.searchRadiusKm() != null) {
            profile.setSearchRadiusKm(dto.searchRadiusKm());
        }
        if (Boolean.TRUE.equals(dto.invisible())) {
            featureAccess.require(profile.getAccountId(), PremiumFeatures.INVISIBLE);
        }
        if (dto.invisible() != null) {
            profile.setInvisible(dto.invisible());
        }
        if (dto.showPremiumBadge() != null) {
            profile.setShowPremiumBadge(dto.showPremiumBadge());
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

    private Set<RelationshipGoal> resolveGoals(List<String> rawCodes, Map<String, String> errors) {
        Set<String> codes = rawCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) {
            return new HashSet<>();
        }
        List<RelationshipGoal> found = goalRepository.findAllByCodeInAndActiveTrue(codes);
        if (found.size() != codes.size()) {
            Set<String> foundCodes = found.stream().map(RelationshipGoal::getCode).collect(Collectors.toSet());
            List<String> unknown = codes.stream().filter(code -> !foundCodes.contains(code)).toList();
            errors.put("goalCodes", "Неизвестные цели: " + String.join(", ", unknown));
        }
        return new HashSet<>(found);
    }

    /**
     * Shares the device location, rounded to 0.01° (~1 km) so nobody can work out an address from distances.
     */
    @Transactional
    public MyProfileDto updateLocation(UUID accountId, double latitude, double longitude) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw ApiException.validation("VALIDATION_ERROR", "Некорректные координаты",
                    Map.of("latitude", "Широта −90..90, долгота −180..180"));
        }
        Profile profile = lockOrCreateProfile(accountId);
        profile.setLocation(Math.round(latitude * 100) / 100.0, Math.round(longitude * 100) / 100.0);
        return toMyProfileDto(profile);
    }

    /** Stops sharing the location: distances fall back to the city centre. */
    @Transactional
    public MyProfileDto clearLocation(UUID accountId) {
        Profile profile = lockOrCreateProfile(accountId);
        profile.setLocation(null, null);
        return toMyProfileDto(profile);
    }

    @Transactional(readOnly = true)
    public List<GoalDto> listGoals() {
        return goalRepository.findAllByActiveTrueOrderBySortOrderAsc().stream().map(GoalDto::from).toList();
    }

    /**
     * Goals of a profile in display order.
     */
    public static List<GoalDto> goalsOf(Profile profile) {
        return profile.getGoals().stream()
                .filter(RelationshipGoal::isActive) // hidden by an admin
                .map(GoalDto::from)
                .sorted(Comparator.comparingInt(GoalDto::sortOrder))
                .toList();
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
                .filter(CommunicationTag::isActive) // hidden by an admin
                .map(TagResponseDto::from)
                .sorted(Comparator.comparingInt(TagResponseDto::sortOrder))
                .toList();
        List<ProfileTips.Tip> tips = ProfileTips.of(profile, photos.size());
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
                ProfileTips.percent(tips),
                tips,
                List.copyOf(missingForPublish(profile, photos.size()).keySet()),
                decorationQuery.publicDecorations(profile.getAccountId()),
                profile.getHeightCm(),
                goalsOf(profile),
                !featureAccess.premiumAmong(List.of(profile.getAccountId())).isEmpty(),
                profile.isVerified(),
                profile.getGender(),
                profile.getSeeking(),
                profile.getBirthDate(),
                profile.getPrefAgeMin(),
                profile.getPrefAgeMax(),
                profile.getCityId(),
                profile.getSearchRadiusKm(),
                profile.hasLocation(),
                identityLocked(profile),
                profile.isInvisible(),
                profile.isShowPremiumBadge(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    /**
     * Gender and age are fixed after the first publication (changes go through support); test accounts may
     * change them freely, so testers can try both sides of the feed.
     */
    private boolean identityLocked(Profile profile) {
        return profile.getPublicationStatus() != PublicationStatus.DRAFT
                && !accounts.isTestAccount(profile.getAccountId());
    }

    /**
     * Single source of the "ready to publish" rule, used both for the hint and for the publish check.
     */
    public static Map<String, String> missingForPublish(Profile profile, long photoCount) {
        Map<String, String> missing = new LinkedHashMap<>();
        if (isBlank(profile.getDisplayName())) {
            missing.put("displayName", "Укажите имя");
        }
        if (profile.getBirthDate() == null) {
            missing.put("birthDate", "Укажите дату рождения (18+)");
        }
        if (profile.getGender() == null) {
            missing.put("gender", "Укажите пол");
        }
        if (profile.getSeeking() == null) {
            missing.put("seeking", "Укажите, кого вы ищете");
        }
        if (isBlank(profile.getCity())) {
            missing.put("city", "Укажите город");
        }
        if (photoCount < 1) {
            missing.put("photos", "Добавьте хотя бы одну фотографию");
        }
        return missing;
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
