package com.dating.discovery.application;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.discovery.api.DiscoveryPageResponseDto;
import com.dating.discovery.api.DiscoveryQueryDto;
import com.dating.discovery.api.TagLogic;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.PublicProfileDto;
import com.dating.profiles.application.ProfilePhotoQuery;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;
import com.dating.profiles.persistence.ProfileRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DiscoveryService {

    private final ProfileRepository profileRepository;
    private final ProfilePhotoQuery photoQuery;

    public DiscoveryService(ProfileRepository profileRepository, ProfilePhotoQuery photoQuery) {
        this.profileRepository = profileRepository;
        this.photoQuery = photoQuery;
    }

    /**
     * All filters, including AND/OR tag logic, run in SQL before pagination, so pages are full
     * and totals are exact.
     */
    @Transactional(readOnly = true)
    public DiscoveryPageResponseDto searchProfiles(UUID callerAccountId, DiscoveryQueryDto query) {
        PageRequest pageRequest = PageRequest.of(query.page(), query.limit(),
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));

        Specification<Profile> spec = Specification.where(isVisible())
                .and(ownerIsActive())
                .and(notOwnedBy(callerAccountId));
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

        Page<Profile> page = profileRepository.findAll(spec, pageRequest);
        Map<UUID, List<PhotoDto>> photos = photoQuery.photosOf(page.getContent().stream().map(Profile::getId).toList());

        List<PublicProfileDto> items = page.getContent().stream()
                .map(p -> PublicProfileDto.from(p, photos.getOrDefault(p.getId(), List.of())))
                .toList();

        return new DiscoveryPageResponseDto(items, query.page(), query.limit(),
                page.getTotalElements(), page.getTotalPages());
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

    private static Specification<Profile> ownerIsActive() {
        return (root, q, cb) -> {
            Subquery<UUID> account = q.subquery(UUID.class);
            Root<Account> a = account.from(Account.class);
            account.select(a.get("id")).where(
                    cb.equal(a.get("id"), root.get("accountId")),
                    cb.equal(a.get("status"), AccountStatus.ACTIVE));
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
