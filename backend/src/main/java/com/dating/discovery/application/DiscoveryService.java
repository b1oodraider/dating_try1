package com.dating.discovery.application;

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
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;
import com.dating.profiles.persistence.ProfileRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    public DiscoveryService(ProfileRepository profileRepository, ProfilePhotoQuery photoQuery,
                            ExclusionWordsService exclusionWords, ProfileDecorationQuery decorationQuery,
                            AccountService accountService, ExcludedGoalsService excludedGoals,
                            FeatureAccess featureAccess, DiscoveryBoosts boosts) {
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
        Specification<Profile> spec = Specification.where(isVisible())
                .and(ownerIsActive(viewerIsTestAccount))
                .and(notOwnedBy(callerAccountId));
        spec = spec.and(contributedExclusions(callerAccountId));
        if (query.city() != null) {
            spec = spec.and(cityEquals(query.city()));
        }
        if (query.minAge() != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("age"), query.minAge()));
        }
        if (query.maxAge() != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("age"), query.maxAge()));
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
        Set<UUID> boostBadges = boosts.publicBadgesAmong(owners);
        Set<UUID> premiumOwners = featureAccess.premiumAmong(page.getContent().stream().map(Profile::getAccountId).toList());
        Map<UUID, List<PhotoDto>> photos = photoQuery.photosOf(page.getContent().stream().map(Profile::getId).toList());
        Map<UUID, Map<String, DecorationDto>> decorations =
                decorationQuery.publicDecorations(page.getContent().stream().map(Profile::getAccountId).toList());

        List<PublicProfileDto> items = page.getContent().stream()
                .map(p -> PublicProfileDto.from(p, photos.getOrDefault(p.getId(), List.of()),
                        decorations.getOrDefault(p.getAccountId(), Map.of()), premiumOwners.contains(p.getAccountId()),
                        boostBadges.contains(p.getAccountId()) ? Boolean.TRUE : null))
                .toList();

        return new DiscoveryPageResponseDto(items, query.page(), query.limit(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Boosted profiles first (they only reorder profiles that matched the filters), then the newest updates.
     * Sets ORDER BY only on the page query, not on the count query.
     */
    private Specification<Profile> feedOrder(UUID viewerAccountId) {
        return (root, q, cb) -> {
            if (!Long.class.equals(q.getResultType())) {
                List<Order> orders = new ArrayList<>(boosts.leadingOrders(root, cb, viewerAccountId));
                orders.add(cb.desc(root.get("updatedAt")));
                orders.add(cb.desc(root.get("id")));
                q.orderBy(orders);
            }
            return cb.conjunction();
        };
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

    private static Specification<Profile> hasAnyGoal(Set<String> goalCodes) {
        return (root, q, cb) -> {
            Subquery<String> matched = q.subquery(String.class);
            Root<Profile> p2 = matched.from(Profile.class);
            Join<Profile, RelationshipGoal> goal = p2.join("goals");
            matched.select(goal.get("code"))
                    .where(cb.equal(p2.get("id"), root.get("id")), goal.get("code").in(goalCodes));
            return cb.exists(matched);
        };
    }

    private static Specification<Profile> isVisible() {
        return (root, q, cb) -> cb.and(
                cb.equal(root.get("publicationStatus"), PublicationStatus.PUBLISHED),
                cb.equal(root.get("visibilityStatus"), VisibilityStatus.PUBLIC),
                cb.isNotNull(root.get("displayName")),
                cb.isNotNull(root.get("age")),
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
