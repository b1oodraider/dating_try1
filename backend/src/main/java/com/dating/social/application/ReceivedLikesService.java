package com.dating.social.application;

import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.entitlements.PremiumFeatures;
import com.dating.profiles.api.PublicProfileDto;
import com.dating.social.api.ReceivedLikeDto;
import com.dating.social.api.ReceivedLikesCountDto;
import com.dating.social.api.ReceivedLikesPageDto;
import com.dating.social.persistence.SocialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * "Who liked me": likes I have not answered yet (no like back, no active pass, no block), super likes first.
 * The list is the premium feature WHO_LIKED_ME (free while it is not listed in app.economy.premium-features);
 * the count is always available.
 */
@Service
public class ReceivedLikesService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final SocialRepository repository;
    private final PublicProfileAssembler profiles;
    private final FeatureAccess featureAccess;
    private final SocialProperties properties;

    public ReceivedLikesService(SocialRepository repository, PublicProfileAssembler profiles,
                                FeatureAccess featureAccess, SocialProperties properties) {
        this.repository = repository;
        this.profiles = profiles;
        this.featureAccess = featureAccess;
        this.properties = properties;
    }

    /**
     * @throws com.dating.common.errors.ApiException 403 PREMIUM_REQUIRED (details.feature = WHO_LIKED_ME)
     */
    @Transactional(readOnly = true)
    public ReceivedLikesPageDto list(UUID me, int page, int limit) {
        featureAccess.require(me, PremiumFeatures.WHO_LIKED_ME);
        page = Math.max(0, page);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        List<SocialRepository.ReceivedLikeRow> rows =
                repository.receivedLikes(me, properties.passTtl(), limit, (long) page * limit);
        Map<UUID, PublicProfileDto> byAccount =
                profiles.byAccountIds(me, rows.stream().map(SocialRepository.ReceivedLikeRow::actorAccountId).toList());
        List<ReceivedLikeDto> items = rows.stream()
                .map(row -> {
                    PublicProfileDto profile = byAccount.get(row.actorAccountId());
                    return profile == null ? null : new ReceivedLikeDto(profile, row.superlike(), row.likedAt());
                })
                .filter(Objects::nonNull)
                .toList();
        long total = repository.countReceivedLikes(me, properties.passTtl()).count();
        int totalPages = (int) ((total + limit - 1) / limit);
        return new ReceivedLikesPageDto(items, page, limit, total, totalPages);
    }

    @Transactional(readOnly = true)
    public ReceivedLikesCountDto count(UUID me) {
        SocialRepository.ReceivedLikesCount count = repository.countReceivedLikes(me, properties.passTtl());
        return new ReceivedLikesCountDto(count.count(), count.superlikeCount());
    }
}
