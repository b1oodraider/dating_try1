package com.dating.profiles.application;

import com.dating.profiles.api.FactsDto;
import com.dating.profiles.persistence.ProfileHistoryRepository;
import com.dating.profiles.persistence.InterestRepository;
import com.dating.profiles.persistence.ArtistRepository;
import com.dating.profiles.domain.InterestCategory;
import com.dating.profiles.domain.Interest;
import com.dating.profiles.domain.Artist;
import com.dating.profiles.api.InterestGroupDto;
import com.dating.profiles.api.InterestDto;
import com.dating.profiles.api.ArtistDto;
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
    private final InterestRepository interestRepository;
    private final ArtistRepository artistRepository;
    private final ProfileHistoryRepository history;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    static final int MAX_INTERESTS = 10;
    static final int MAX_INTEREST_COMMENT = 80;
    static final int MAX_ARTISTS = 5;
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
                          com.dating.accounts.application.AccountService accounts,
                          InterestRepository interestRepository,
                          ArtistRepository artistRepository,
                          ProfileHistoryRepository history,
                          tools.jackson.databind.json.JsonMapper jsonMapper) {
        this.interestRepository = interestRepository;
        this.artistRepository = artistRepository;
        this.history = history;
        this.jsonMapper = jsonMapper;
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
            // Interest comments are free text shown in the same profile: they go with «О себе».
            profile.setAboutText(null);
            profile.clearInterestComments();
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
        Set<Interest> interests = null;
        if (dto.interestCodes() != null) {
            interests = resolveInterests(dto.interestCodes(), errors);
        }
        Map<String, String> interestComments = null;
        if (dto.interestComments() != null) {
            Set<String> chosen = (interests != null ? interests : profile.getInterests()).stream()
                    .map(Interest::getCode).collect(Collectors.toSet());
            interestComments = resolveInterestComments(dto.interestComments(), chosen, errors);
        }
        Set<Artist> artists = null;
        if (dto.artistIds() != null) {
            artists = resolveArtists(dto.artistIds(), errors);
        }
        checkFact("kidsHas", dto.kidsHas(), ProfileFacts.KIDS_HAS, errors);
        checkFact("kidsPlans", dto.kidsPlans(), ProfileFacts.KIDS_PLANS, errors);
        checkFact("smoking", dto.smoking(), ProfileFacts.SMOKING, errors);
        checkFact("alcohol", dto.alcohol(), ProfileFacts.ALCOHOL, errors);
        checkFact("sport", dto.sport(), ProfileFacts.SPORT, errors);
        checkFact("educationLevel", dto.educationLevel(), ProfileFacts.EDUCATION, errors);
        checkFacts("pets", dto.pets(), ProfileFacts.PETS, 4, errors);
        checkFacts("languages", dto.languages(), ProfileFacts.LANGUAGES, 6, errors);
        if (dto.pets() != null && dto.pets().contains("NONE") && dto.pets().size() > 1) {
            errors.put("pets", "«Нет животных» нельзя выбрать вместе с животными");
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
            String code = errors.getOrDefault("tagCodes", "").startsWith(ONE_PER_CATEGORY) ? "ONE_TAG_PER_CATEGORY"
                    : errors.containsKey("tagCodes") ? "UNKNOWN_TAG"
                    : errors.containsKey("goalCodes") ? "UNKNOWN_GOAL" : "VALIDATION_ERROR";
            throw ApiException.validation(code, "Ошибка валидации входных данных", errors);
        }
        if (clearHeight) {
            profile.setHeightCm(null);
        } else if (dto.heightCm() != null) {
            profile.setHeightCm(dto.heightCm());
        }
        Map<String, Object> before = snapshot(profile);
        if (goals != null) {
            profile.replaceGoals(goals);
        }
        if (interests != null) {
            profile.replaceInterests(interests);
        }
        if (interestComments != null) {
            interestComments.forEach(profile::setInterestComment);
        }
        if (artists != null) {
            profile.replaceArtists(artists);
        }
        if (dto.showZodiac() != null) {
            profile.setShowZodiac(dto.showZodiac());
        }
        if (dto.kidsHas() != null) {
            profile.setKidsHas(blankToNull(dto.kidsHas()));
        }
        if (dto.kidsPlans() != null) {
            profile.setKidsPlans(blankToNull(dto.kidsPlans()));
        }
        if (dto.smoking() != null) {
            profile.setSmoking(blankToNull(dto.smoking()));
        }
        if (dto.alcohol() != null) {
            profile.setAlcohol(blankToNull(dto.alcohol()));
        }
        if (dto.sport() != null) {
            profile.setSport(blankToNull(dto.sport()));
        }
        if (dto.pets() != null) {
            profile.setPets(dto.pets().stream().distinct().toList());
        }
        if (dto.profession() != null) {
            profile.setProfession(blankToNull(dto.profession()));
        }
        if (dto.educationLevel() != null) {
            profile.setEducationLevel(blankToNull(dto.educationLevel()));
        }
        if (dto.university() != null) {
            profile.setUniversity(blankToNull(dto.university()));
        }
        if (dto.languages() != null) {
            profile.setLanguages(dto.languages().stream().distinct().toList());
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
        if (!before.equals(snapshot(profile))) {
            history.save(profile.getId(), jsonMapper.writeValueAsString(before));
        }

        return toMyProfileDto(profile);
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** One answer from the fixed list, or "" to clear. */
    private static void checkFact(String field, String value, Map<String, String> allowed, Map<String, String> errors) {
        if (value != null && !value.isBlank() && !allowed.containsKey(value.trim())) {
            errors.put(field, "Неизвестное значение: " + value);
        }
    }

    private static void checkFacts(String field, List<String> values, Map<String, String> allowed, int max,
                                   Map<String, String> errors) {
        if (values == null) {
            return;
        }
        if (values.size() > max) {
            errors.put(field, "Не больше " + max);
        } else if (!allowed.keySet().containsAll(values)) {
            errors.put(field, "Неизвестные значения");
        }
    }

    /** What a person filled in (no photos: they keep their own rows); the previous one goes to profile_history. */
    private static Map<String, Object> snapshot(Profile p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("displayName", p.getDisplayName());
        s.put("birthDate", p.getBirthDate() == null ? null : p.getBirthDate().toString());
        s.put("gender", p.getGender() == null ? null : p.getGender().name());
        s.put("seeking", p.getSeeking() == null ? null : p.getSeeking().name());
        s.put("ageFrom", p.getPrefAgeMin());
        s.put("ageTo", p.getPrefAgeMax());
        s.put("city", p.getCity());
        s.put("cityId", p.getCityId());
        s.put("aboutText", p.getAboutText());
        s.put("dailyStatusText", p.getDailyStatusText());
        s.put("heightCm", p.getHeightCm());
        s.put("goals", p.getGoals().stream().map(RelationshipGoal::getCode).sorted().toList());
        s.put("tags", p.getTags().stream().map(CommunicationTag::getCode).sorted().toList());
        s.put("interests", p.getInterests().stream().map(Interest::getCode).sorted().toList());
        s.put("interestComments", new java.util.TreeMap<>(p.getInterestComments()));
        s.put("artists", p.getArtists().stream().map(Artist::getId).sorted().toList());
        s.put("facts", FactsDto.from(p));
        return s;
    }

    private static Map<String, String> resolveInterestComments(Map<String, String> raw, Set<String> chosen,
                                                               Map<String, String> errors) {
        Map<String, String> comments = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String comment = entry.getValue() == null ? "" : entry.getValue().strip().replaceAll("\\s+", " ");
            if (!chosen.contains(entry.getKey())) {
                errors.put("interestComments", "Комментарий можно оставить только к выбранному увлечению");
            } else if (comment.length() > MAX_INTEREST_COMMENT) {
                errors.put("interestComments", "Комментарий — не длиннее " + MAX_INTEREST_COMMENT + " символов");
            } else {
                comments.put(entry.getKey(), comment);
            }
        }
        return comments;
    }

    private Set<Interest> resolveInterests(List<String> rawCodes, Map<String, String> errors) {
        Set<String> codes = rawCodes.stream().filter(Objects::nonNull).map(String::trim)
                .filter(code -> !code.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.size() > MAX_INTERESTS) {
            errors.put("interestCodes", "Не больше " + MAX_INTERESTS + " увлечений");
            return new HashSet<>();
        }
        List<Interest> found = codes.isEmpty() ? List.of() : interestRepository.findAllByCodeInAndActiveTrue(codes);
        if (found.size() != codes.size()) {
            Set<String> known = found.stream().map(Interest::getCode).collect(Collectors.toSet());
            errors.put("interestCodes", "Неизвестные увлечения: "
                    + codes.stream().filter(c -> !known.contains(c)).collect(Collectors.joining(", ")));
        }
        return new HashSet<>(found);
    }

    private Set<Artist> resolveArtists(List<Long> rawIds, Map<String, String> errors) {
        Set<Long> ids = rawIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.size() > MAX_ARTISTS) {
            errors.put("artistIds", "Не больше " + MAX_ARTISTS + " исполнителей");
            return new HashSet<>();
        }
        List<Artist> found = ids.isEmpty() ? List.of() : artistRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            errors.put("artistIds", "Неизвестные исполнители");
        }
        return new HashSet<>(found);
    }

    public static List<InterestDto> interestsOf(Profile profile) {
        return profile.getInterests().stream()
                .filter(Interest::isActive)
                .sorted(Comparator.comparingInt((Interest i) -> i.getCategory().getSortOrder())
                        .thenComparingInt(Interest::getSortOrder))
                .map(interest -> InterestDto.from(interest, profile.getInterestComments().get(interest.getCode())))
                .toList();
    }

    public static List<ArtistDto> artistsOf(Profile profile) {
        return profile.getArtists().stream()
                .sorted(Comparator.comparing(Artist::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ArtistDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InterestGroupDto> listInterests() {
        Map<InterestCategory, List<Interest>> byCategory = interestRepository.findAllByActiveTrue().stream()
                .collect(Collectors.groupingBy(Interest::getCategory));
        return byCategory.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getKey().getSortOrder()))
                .map(e -> new InterestGroupDto(e.getKey().getCode(), e.getKey().getLabel(),
                        e.getValue().stream().sorted(Comparator.comparingInt(Interest::getSortOrder))
                                .map(InterestDto::from).toList()))
                .toList();
    }

    private static final String ONE_PER_CATEGORY = "В каждой категории — одна карточка: ";

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
            return new HashSet<>(found);
        }
        // One answer per category (docs/matching.md, part 6): the cards of a category are steps of one scale.
        List<String> crowded = found.stream()
                .collect(Collectors.groupingBy(CommunicationTag::getCategoryLabel, Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
        if (!crowded.isEmpty()) {
            errors.put("tagCodes", ONE_PER_CATEGORY + String.join(", ", crowded));
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
                interestsOf(profile),
                artistsOf(profile),
                profile.isShowZodiac(),
                Zodiac.of(profile.getBirthDate()),
                FactsDto.from(profile),
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
