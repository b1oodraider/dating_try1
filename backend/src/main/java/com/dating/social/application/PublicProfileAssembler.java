package com.dating.social.application;

import com.dating.communities.api.CommunityRefDto;
import com.dating.communities.application.CommunityService;
import com.dating.profiles.application.CompatibilityService;
import com.dating.common.entitlements.FeatureAccess;
import com.dating.profiles.api.DecorationDto;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.PublicProfileDto;
import com.dating.profiles.application.ProfileDecorationQuery;
import com.dating.profiles.application.ProfilePhotoQuery;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds PublicProfile DTOs (the same shape as in the feed) for a batch of accounts:
 * photos, public cosmetics and the premium badge are loaded with one query each.
 */
@Component
public class PublicProfileAssembler {

    private final ProfileRepository profileRepository;
    private final ProfilePhotoQuery photoQuery;
    private final ProfileDecorationQuery decorationQuery;
    private final FeatureAccess featureAccess;
    private final CompatibilityService compatibilityService;
    private final CommunityService communities;

    public PublicProfileAssembler(ProfileRepository profileRepository, ProfilePhotoQuery photoQuery,
                                  ProfileDecorationQuery decorationQuery, FeatureAccess featureAccess,
                                  CompatibilityService compatibilityService, CommunityService communities) {
        this.communities = communities;
        this.compatibilityService = compatibilityService;
        this.profileRepository = profileRepository;
        this.photoQuery = photoQuery;
        this.decorationQuery = decorationQuery;
        this.featureAccess = featureAccess;
    }

    /**
     * @param viewer whose card compatibility to show (null = none)
     * @return profile by account id; accounts without a profile are absent from the map
     */
    @Transactional(readOnly = true)
    public Map<UUID, PublicProfileDto> byAccountIds(UUID viewer, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Specification<Profile> ofAccounts = (root, q, cb) -> root.get("accountId").in(accountIds);
        List<Profile> profiles = profileRepository.findAll(ofAccounts);
        List<UUID> owners = profiles.stream().map(Profile::getAccountId).toList();
        Set<UUID> premiumOwners = featureAccess.premiumAmong(owners);
        Map<UUID, List<PhotoDto>> photos = photoQuery.photosOf(profiles.stream().map(Profile::getId).toList());
        Map<UUID, Map<String, DecorationDto>> decorations = decorationQuery.publicDecorations(owners);

        Map<UUID, Integer> compatibility = compatibilityService.forViewer(viewer, profiles);
        Map<UUID, List<CommunityRefDto>> groups = communities.of(viewer, owners);
        Map<UUID, PublicProfileDto> result = new HashMap<>();
        for (Profile p : profiles) {
            result.put(p.getAccountId(), PublicProfileDto.from(p, photos.getOrDefault(p.getId(), List.of()),
                    decorations.getOrDefault(p.getAccountId(), Map.of()), premiumOwners.contains(p.getAccountId()),
                    compatibility.get(p.getAccountId()), null)
                    .withCommunities(groups.getOrDefault(p.getAccountId(), List.of())));
        }
        return result;
    }
}
