package com.dating.social.application;

import com.dating.safety.application.RestrictionGuard;
import com.dating.safety.domain.RestrictionKind;
import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyService;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.social.api.ReactionResultDto;
import com.dating.social.domain.MatchInfo;
import com.dating.social.domain.ReactionType;
import com.dating.social.persistence.SocialRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Likes, super likes and passes from the feed, and match creation on a mutual like.
 * <p>
 * Everything for one pair runs under a pair lock (pg advisory lock), so two people liking each other at
 * the same moment see each other's like and exactly one match is created; a retried super like cannot
 * spend twice.
 */
@Service
public class ReactionService {

    /** Catalog code of the super like consumable. */
    public static final String SUPERLIKE_ITEM = "SUPERLIKE";

    private final SocialRepository repository;
    private final SocialTargets targets;
    private final MatchService matchService;
    private final EconomyService economyService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductEventPublisher productEvents;
    private final RestrictionGuard restrictionGuard;

    public ReactionService(SocialRepository repository, SocialTargets targets, MatchService matchService,
                           EconomyService economyService, ApplicationEventPublisher eventPublisher,
                           ProductEventPublisher productEvents, RestrictionGuard restrictionGuard) {
        this.restrictionGuard = restrictionGuard;
        this.repository = repository;
        this.targets = targets;
        this.matchService = matchService;
        this.economyService = economyService;
        this.eventPublisher = eventPublisher;
        this.productEvents = productEvents;
    }

    @Transactional
    public ReactionResultDto like(UUID me, UUID profileId) {
        restrictionGuard.requireAllowed(me, RestrictionKind.LIKES);
        SocialTargets.Counterpart target = targets.requireDiscoverable(me, profileId);
        return react(me, target.accountId(), ReactionType.LIKE, null);
    }

    /**
     * Spends one SUPERLIKE (409 INSUFFICIENT_BALANCE). The spend is idempotent per (target, idempotencyKey),
     * and an already super-liked target is never charged again.
     */
    @Transactional
    public ReactionResultDto superlike(UUID me, UUID profileId, String idempotencyKey) {
        restrictionGuard.requireAllowed(me, RestrictionKind.LIKES);
        SocialTargets.Counterpart target = targets.requireDiscoverable(me, profileId);
        return react(me, target.accountId(), ReactionType.SUPERLIKE,
                "superlike:" + target.accountId() + ":" + idempotencyKey);
    }

    @Transactional
    public ReactionResultDto pass(UUID me, UUID profileId) {
        SocialTargets.Counterpart target = targets.requireDiscoverable(me, profileId);
        return react(me, target.accountId(), ReactionType.PASS, null);
    }

    /**
     * Testing API: a reaction between accounts without feed checks and without spending super likes.
     */
    @Transactional
    public ReactionResultDto reactForTesting(UUID actor, UUID target, ReactionType type) {
        return react(actor, target, type, null);
    }

    /**
     * Testing API: both sides like each other and an active match exists afterwards (created if needed,
     * even if an earlier match of the pair was closed).
     */
    @Transactional
    public UUID matchForTesting(UUID a, UUID b) {
        repository.lockPair(a, b);
        for (UUID[] pair : new UUID[][]{{a, b}, {b, a}}) {
            Optional<ReactionType> current = repository.findReaction(pair[0], pair[1]);
            if (current.filter(ReactionType::isLike).isEmpty()) {
                repository.upsertReaction(pair[0], pair[1], ReactionType.LIKE);
            }
        }
        return matchService.createOrGet(a, b);
    }

    /**
     * Testing API: forget my reactions (all of them, or only passes) so the profiles come back to the feed.
     * Matches are not touched.
     */
    @Transactional
    public int resetReactions(UUID me, boolean passesOnly) {
        return repository.deleteReactionsOf(me, passesOnly);
    }

    private ReactionResultDto react(UUID actor, UUID target, ReactionType type, String spendKey) {
        repository.lockPair(actor, target);
        ReactionType current = repository.findReaction(actor, target).orElse(null);

        if (type == ReactionType.PASS) {
            if (current != null && current.isLike()) {
                throw new ApiException("ALREADY_LIKED", "Вы уже лайкнули эту анкету", HttpStatus.CONFLICT);
            }
            repository.upsertReaction(actor, target, ReactionType.PASS); // a repeated pass restarts the TTL
            productEvents.publish("profile_passed", Map.of());
            return ReactionResultDto.notMatched();
        }

        Optional<MatchInfo> active = repository.findActiveMatchBetween(actor, target);
        if (active.isPresent()) {
            return ReactionResultDto.matched(active.get().id()); // already matched: nothing to change or spend
        }

        boolean wasLiked = current != null && current.isLike();
        boolean unchanged = current == type || (type == ReactionType.LIKE && current == ReactionType.SUPERLIKE);
        if (unchanged) {
            return ReactionResultDto.notMatched(); // idempotent repeat
        }
        if (type == ReactionType.SUPERLIKE && spendKey != null) {
            economyService.spend(actor, SUPERLIKE_ITEM, 1, target.toString(), spendKey);
        }
        repository.upsertReaction(actor, target, type);
        productEvents.publish(type == ReactionType.SUPERLIKE ? "profile_superliked" : "profile_liked", Map.of());

        // Only a new like can create a match: re-liking (e.g. like → super like) never resurrects an unmatch.
        boolean likedBack = repository.findReaction(target, actor).filter(ReactionType::isLike).isPresent();
        if (!wasLiked && likedBack) {
            return ReactionResultDto.matched(matchService.createOrGet(actor, target));
        }
        eventPublisher.publishEvent(new SocialEvents.LikeReceived(target, type == ReactionType.SUPERLIKE));
        return ReactionResultDto.notMatched();
    }
}
