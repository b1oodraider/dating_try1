package com.dating.discovery.application;

import com.dating.profiles.application.CompatibilityService;
import com.dating.safety.application.BehaviorService;
import com.dating.safety.application.VerificationGateProperties;
import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.discovery.api.DiscoveryPageResponseDto;
import com.dating.discovery.api.DiscoveryQueryDto;
import com.dating.discovery.api.TagLogic;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.PublicProfileDto;
import com.dating.accounts.application.AccountService;
import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.entitlements.PremiumFeatures;
import com.dating.profiles.domain.RelationshipGoal;
import com.dating.profiles.api.DecorationDto;
import com.dating.profiles.application.ProfileDecorationQuery;
import com.dating.profiles.application.ProfilePhotoQuery;
import com.dating.common.errors.ApiException;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import java.util.stream.Collectors;
import com.dating.profiles.domain.Artist;
import com.dating.profiles.domain.Interest;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;
import com.dating.profiles.persistence.CityRepository;
import com.dating.profiles.persistence.ProfileRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DiscoveryService {

    private final ProfileRepository profileRepository;
    private final ProfilePhotoQuery photoQuery;
    private final ExclusionWordsService exclusionWords;
    private final ProfileDecorationQuery decorationQuery;
    private final AccountService accountService;
    private final ExcludedGoalsService excludedGoals;
    private final FeatureAccess featureAccess;
    private final DiscoveryBoosts boosts;
    private final BehaviorService behavior;
    private final CompatibilityService compatibilityService;
    private final CityRepository cities;
    private final VerificationGateProperties gate;

    public DiscoveryService(ProfileRepository profileRepository, ProfilePhotoQuery photoQuery,
                            ExclusionWordsService exclusionWords, ProfileDecorationQuery decorationQuery,
                            AccountService accountService, ExcludedGoalsService excludedGoals,
                            FeatureAccess featureAccess, DiscoveryBoosts boosts, BehaviorService behavior,
                            CompatibilityService compatibilityService, CityRepository cities,
                            VerificationGateProperties gate) {
        this.gate = gate;
        this.cities = cities;
        this.behavior = behavior;
        this.compatibilityService = compatibilityService;
        this.profileRepository = profileRepository;
        this.photoQuery = photoQuery;
        this.exclusionWords = exclusionWords;
        this.decorationQuery = decorationQuery;
        this.accountService = accountService;
        this.excludedGoals = excludedGoals;
        this.featureAccess = featureAccess;
        this.boosts = boosts;
    }

    /**
     * All filters, including AND/OR tag logic, run in SQL before pagination, so pages are full
     * and totals are exact. Boosted profiles go first (see {@link #feedOrder}); serving a page counts boost
     * impressions, hence a read-write transaction.
     */
    @Transactional
    public DiscoveryPageResponseDto searchProfiles(UUID callerAccountId, DiscoveryQueryDto query) {
        PageRequest pageRequest = PageRequest.of(query.page(), query.limit()); // order: feedOrder()

        // Test accounts and real users never see each other.
        boolean viewerIsTestAccount = accountService.isTestAccount(callerAccountId);
        Profile viewer = requireMatchingProfile(callerAccountId);
        Specification<Profile> spec = Specification.where(isVisible())
                .and(ownerIsActive(viewerIsTestAccount))
                .and(notOwnedBy(callerAccountId))
                .and(mutualMatch(viewer, query))
                .and(notInvisibleTo(callerAccountId));
        if (!viewerIsTestAccount) { // seed profiles of test accounts never log in
            spec = spec.and(recentlyActive());
        }
        if (gate.enabled() && !viewerIsTestAccount) {
            spec = spec.and((root, q, cb) -> cb.isNotNull(root.get("verifiedAt")));
            if (!viewer.isVerified()) {
                int left = gate.dailyViews() - behavior.profilesShownToday(callerAccountId);
                if (left <= 0) {
                    throw ApiException.withDetails("DAILY_VIEWS_LIMIT", "Без подтверждения селфи можно посмотреть "
                            + gate.dailyViews() + " анкет в день. Пройдите проверку — и ограничений не будет",
                            HttpStatus.TOO_MANY_REQUESTS, Map.of("limit", gate.dailyViews()));
                }
                // shortcut: a smaller last page shifts offsets of later pages; clients refill from page 0 anyway
                pageRequest = PageRequest.of(query.page(), Math.min(query.limit(), left));
            }
        }
        spec = spec.and(contributedExclusions(callerAccountId));
        Integer cityId = query.cityId();
        if (cityId == null && query.city() != null) {
            cityId = cities.resolve(query.city()).map(CityRepository.City::id).orElse(null);
            if (cityId == null) {
                spec = spec.and(cityEquals(query.city())); // shortcut: unknown free text; drop with the legacy param
            }
        }
        if (cityId != null) {
            Integer id = cityId;
            spec = spec.and((root, q, cb) -> cb.equal(root.get("cityId"), id));
        }
        double[] viewerPoint = pointOf(viewer);
        Integer radius = query.radiusKm() != null ? query.radiusKm() : viewer.getSearchRadiusKm();
        if (radius != null && viewerPoint != null) {
            spec = spec.and(withinRadius(viewerPoint, radius, callerAccountId));
        }
        if (!query.tagCodes().isEmpty()) {
            spec = spec.and(hasTags(query.tagCodes(), query.tagLogic()));
        }
        if (query.usesAdvancedFilters()) {
            featureAccess.require(callerAccountId, PremiumFeatures.ADVANCED_FILTERS);
        }
        if (query.minHeight() != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("heightCm"), query.minHeight()));
        }
        if (query.maxHeight() != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("heightCm"), query.maxHeight()));
        }
        if (!query.goalCodes().isEmpty()) {
            spec = spec.and(hasAnyGoal(query.goalCodes()));
        }
        // Saved hard filters: kept when premium ends, applied only while the feature is available.
        Set<String> goalsToExclude = excludedGoals.activeGoalsOf(callerAccountId);
        if (!goalsToExclude.isEmpty()) {
            spec = spec.and(Specification.not(hasAnyGoal(goalsToExclude)));
        }
        List<String> excluded = exclusionWords.activeWordsOf(callerAccountId);
        if (!excluded.isEmpty()) {
            spec = spec.and(mentionsNone(ExclusionWordsService.toTsQuery(excluded)));
        }

        Page<Profile> page = profileRepository.findAll(spec.and(feedOrder(callerAccountId)), pageRequest);
        List<UUID> owners = page.getContent().stream().map(Profile::getAccountId).toList();
        boosts.recordImpressions(callerAccountId, owners);
        behavior.recordImpressions(callerAccountId, owners);
        Set<UUID> boostBadges = boosts.publicBadgesAmong(owners);
        Set<UUID> premiumOwners = featureAccess.premiumAmong(page.getContent().stream().map(Profile::getAccountId).toList());
        Map<UUID, List<PhotoDto>> photos = photoQuery.photosOf(page.getContent().stream().map(Profile::getId).toList());
        Map<UUID, Map<String, DecorationDto>> decorations =
                decorationQuery.publicDecorations(page.getContent().stream().map(Profile::getAccountId).toList());

        Map<UUID, Integer> compatibility = compatibilityService.forViewer(callerAccountId, page.getContent());
        List<PublicProfileDto> items = page.getContent().stream()
                .map(p -> PublicProfileDto.from(p, photos.getOrDefault(p.getId(), List.of()),
                        decorations.getOrDefault(p.getAccountId(), Map.of()), premiumOwners.contains(p.getAccountId()),
                        compatibility.get(p.getAccountId()),
                        boostBadges.contains(p.getAccountId()) ? Boolean.TRUE : null)
                        .withDistanceKm(sharedDistanceKm(viewer, p))
                        .withCommon(commonWith(viewer, p)))
                .toList();

        return new DiscoveryPageResponseDto(items, query.page(), query.limit(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Best score first (SQL feed_score, migration 024: distance, goals, quality, recency, boost, newcomer, the
     * candidate's radius, recently shown, liked me at random places); never by the date of the last edit.
     * Sets ORDER BY only on the page query, not on the count query.
     * shortcut: the score is computed per candidate row on every page; precompute when a city has tens of
     * thousands of matching profiles.
     */
    private Specification<Profile> feedOrder(UUID viewerAccountId) {
        return (root, q, cb) -> {
            if (!Long.class.equals(q.getResultType())) {
                q.orderBy(List.<Order>of(
                        cb.desc(cb.function("feed_score", Double.class, cb.literal(viewerAccountId), root.get("id"))),
                        cb.asc(root.get("id"))));
            }
            return cb.conjunction();
        };
    }

    /**
     * The viewer's own gender, who they look for and birth date drive the two-way filters; without them there
     * is nothing to match, so the feed asks to fill them in (400 PROFILE_INCOMPLETE keyed by the missing fields).
     */
    private Profile requireMatchingProfile(UUID accountId) {
        Profile viewer = profileRepository.findByAccountId(accountId).orElse(null);
        Map<String, String> missing = new LinkedHashMap<>();
        if (viewer == null || viewer.getGender() == null) {
            missing.put("gender", "Укажите пол");
        }
        if (viewer == null || viewer.getSeeking() == null) {
            missing.put("seeking", "Укажите, кого вы ищете");
        }
        if (viewer == null || viewer.getBirthDate() == null) {
            missing.put("birthDate", "Укажите дату рождения");
        }
        if (!missing.isEmpty()) {
            throw ApiException.validation("PROFILE_INCOMPLETE", "Заполните пол, кого ищете и дату рождения", missing);
        }
        return viewer;
    }

    /**
     * Hard filters that must hold both ways (docs/matching.md, part 1): each one is who the other looks for,
     * each one's age is in the other's range, and their goals meet. The viewer's range is their saved one,
     * optionally narrowed by the query.
     */
    static Specification<Profile> mutualMatch(Profile viewer, DiscoveryQueryDto query) {
        LocalDate today = LocalDate.now();
        int viewerAge = viewer.getAge();
        int minAge = Math.max(viewer.getPrefAgeMin(), query.minAge() == null ? 0 : query.minAge());
        Integer maxAge = viewer.getPrefAgeMax();
        if (query.maxAge() != null) {
            maxAge = maxAge == null ? query.maxAge() : Math.min(maxAge, query.maxAge());
        }
        Integer maxAgeFinal = maxAge;
        Specification<Profile> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("gender"), viewer.getSeeking()),
                cb.equal(root.get("seeking"), viewer.getGender()),
                cb.lessThanOrEqualTo(root.get("birthDate"), today.minusYears(minAge)),
                maxAgeFinal == null ? cb.conjunction()
                        : cb.greaterThan(root.get("birthDate"), today.minusYears(maxAgeFinal + 1L)),
                cb.lessThanOrEqualTo(root.get("prefAgeMin"), viewerAge),
                cb.or(cb.isNull(root.get("prefAgeMax")), cb.greaterThanOrEqualTo(root.get("prefAgeMax"), viewerAge)));
        Set<String> goals = new HashSet<>();
        viewer.getGoals().forEach(goal -> goals.add(goal.getCode()));
        if (!goals.isEmpty() && !goals.contains(NOT_SURE_GOAL)) {
            // Unknown goals (none set, or "not sure") never shut anyone out.
            goals.add(NOT_SURE_GOAL);
            spec = spec.and(Specification.not(hasAnyGoal(null)).or(hasAnyGoal(goals)));
        }
        return spec;
    }

    static final String NOT_SURE_GOAL = "NOT_SURE";

    /** The shared location, else the centre of the city; null when neither is known. */
    private double[] pointOf(Profile profile) {
        if (profile.hasLocation()) {
            return new double[]{profile.getLatitude(), profile.getLongitude()};
        }
        return profile.getCityId() == null ? null
                : cities.findById(profile.getCityId()).map(c -> new double[]{c.latitude(), c.longitude()}).orElse(null);
    }

    /**
     * Within the viewer's radius (own location or city centre on both sides), or the candidate already liked the
     * viewer: such a like must not go unanswered just because the radii differ (docs/matching.md, part 1).
     * shortcut: distance is computed per candidate row without a spatial index; add one (earthdistance/PostGIS)
     * when a city has tens of thousands of profiles.
     */
    private static Specification<Profile> withinRadius(double[] point, int radiusKm, UUID viewerAccountId) {
        return (root, q, cb) -> cb.or(
                cb.lessThanOrEqualTo(cb.function("profile_distance_km", Double.class,
                        cb.literal(point[0]), cb.literal(point[1]), root.get("id")), (double) radiusKm),
                cb.isTrue(cb.function("has_liked", Boolean.class, root.get("accountId"), cb.literal(viewerAccountId))));
    }

    /** Up to two things both people chose: interests first, then artists, then communication cards. */
    static List<String> commonWith(Profile viewer, Profile candidate) {
        List<String> common = new ArrayList<>();
        Set<String> myInterests = viewer.getInterests().stream().map(Interest::getCode).collect(Collectors.toSet());
        candidate.getInterests().stream().filter(i -> i.isActive() && myInterests.contains(i.getCode()))
                .map(Interest::getLabel).sorted().forEach(common::add);
        Set<Long> myArtists = viewer.getArtists().stream().map(Artist::getId).collect(Collectors.toSet());
        candidate.getArtists().stream().filter(a -> myArtists.contains(a.getId()))
                .map(Artist::getName).sorted().forEach(common::add);
        Set<String> myTags = viewer.getTags().stream().map(CommunicationTag::getCode).collect(Collectors.toSet());
        candidate.getTags().stream().filter(t -> t.isActive() && myTags.contains(t.getCode()))
                .map(CommunicationTag::getLabel).sorted().forEach(common::add);
        return common.size() > 2 ? List.copyOf(common.subList(0, 2)) : common;
    }

    /** Whole km, at least 1; only when both share their location. */
    static Integer sharedDistanceKm(Profile a, Profile b) {
        if (!a.hasLocation() || !b.hasLocation()) {
            return null;
        }
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.getLatitude()))
                * Math.cos(Math.toRadians(b.getLatitude())) * Math.pow(Math.sin(dLon / 2), 2);
        return (int) Math.max(1, Math.round(2 * 6371 * Math.asin(Math.sqrt(h))));
    }

    // ---------------------------------------------------------------- filters contributed by other modules

    /** E.g. social: already liked / passed profiles and blocks. Setter injection keeps the constructor stable. */
    private List<DiscoveryExclusion> exclusions = List.of();

    @Autowired(required = false)
    void setExclusions(List<DiscoveryExclusion> exclusions) {
        this.exclusions = List.copyOf(exclusions);
    }

    private Specification<Profile> contributedExclusions(UUID viewerAccountId) {
        Specification<Profile> spec = (root, q, cb) -> cb.conjunction();
        for (DiscoveryExclusion exclusion : exclusions) {
            spec = spec.and(exclusion.visibleTo(viewerAccountId));
        }
        return spec;
    }

    /** @param goalCodes null = any goal at all */
    private static Specification<Profile> hasAnyGoal(Set<String> goalCodes) {
        return (root, q, cb) -> {
            Subquery<String> matched = q.subquery(String.class);
            Root<Profile> p2 = matched.from(Profile.class);
            Join<Profile, RelationshipGoal> goal = p2.join("goals");
            matched.select(goal.get("code")).where(goalCodes == null
                    ? cb.equal(p2.get("id"), root.get("id"))
                    : cb.and(cb.equal(p2.get("id"), root.get("id")), goal.get("code").in(goalCodes)));
            return cb.exists(matched);
        };
    }

    private static Specification<Profile> isVisible() {
        return (root, q, cb) -> cb.and(
                cb.equal(root.get("publicationStatus"), PublicationStatus.PUBLISHED),
                cb.equal(root.get("visibilityStatus"), VisibilityStatus.PUBLIC),
                cb.isNotNull(root.get("displayName")),
                cb.isNotNull(root.get("birthDate")),
                cb.isNotNull(root.get("gender")),
                cb.isNotNull(root.get("seeking")),
                cb.isNotNull(root.get("city"))
        );
    }

    private static Specification<Profile> ownerIsActive(boolean testAccounts) {
        return (root, q, cb) -> {
            Subquery<UUID> account = q.subquery(UUID.class);
            Root<Account> a = account.from(Account.class);
            account.select(a.get("id")).where(
                    cb.equal(a.get("id"), root.get("accountId")),
                    cb.equal(a.get("status"), AccountStatus.ACTIVE),
                    cb.equal(a.get("testAccount"), testAccounts));
            return cb.exists(account);
        };
    }

    /** Invisible mode works only while premium is on, and never hides from people the owner liked. */
    private static Specification<Profile> notInvisibleTo(UUID viewerAccountId) {
        return (root, q, cb) -> cb.or(
                cb.isFalse(root.get("invisible")),
                cb.isFalse(cb.function("has_premium", Boolean.class, root.get("accountId"))),
                cb.isTrue(cb.function("has_liked", Boolean.class, root.get("accountId"), cb.literal(viewerAccountId))));
    }

    /** Active within {@link #ACTIVE_WITHIN_DAYS} days, or the profile is newer than that. */
    private static Specification<Profile> recentlyActive() {
        return (root, q, cb) -> cb.or(
                cb.isTrue(cb.function("active_within_days", Boolean.class, root.get("accountId"),
                        cb.literal(ACTIVE_WITHIN_DAYS))),
                cb.greaterThan(root.get("createdAt"), Instant.now().minus(Duration.ofDays(ACTIVE_WITHIN_DAYS))));
    }

    static final int ACTIVE_WITHIN_DAYS = 30;

    private static Specification<Profile> notOwnedBy(UUID accountId) {
        return (root, q, cb) -> cb.notEqual(root.get("accountId"), accountId);
    }

    private static Specification<Profile> cityEquals(String city) {
        String normalized = city.toLowerCase(Locale.ROOT);
        return (root, q, cb) -> cb.equal(cb.lower(root.get("city")), normalized);
    }

    /**
     * Hard filter by the caller's exclusion words: PostgreSQL full-text search with Russian stemming
     * over "about" (precomputed column) and the active daily status. See migration 004.
     */
    private static Specification<Profile> mentionsNone(String tsQuery) {
        return (root, q, cb) -> cb.isFalse(cb.function("profile_has_excluded_words", Boolean.class,
                root.get("id"), cb.literal(tsQuery)));
    }

    private static Specification<Profile> hasTags(Set<String> codes, TagLogic logic) {
        return (root, q, cb) -> {
            Subquery<Long> matched = q.subquery(Long.class);
            Root<Profile> p2 = matched.from(Profile.class);
            Join<Profile, CommunicationTag> tag = p2.join("tags");
            matched.select(cb.countDistinct(tag.get("code")))
                    .where(cb.equal(p2.get("id"), root.get("id")), tag.get("code").in(codes));
            return logic == TagLogic.AND
                    ? cb.equal(matched, (long) codes.size())
                    : cb.greaterThan(matched, 0L);
        };
    }
}
