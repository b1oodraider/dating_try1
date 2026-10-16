package com.dating.safety.application;

import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.persistence.BehaviorRepository;
import com.dating.safety.persistence.BehaviorRepository.ReactionEvent;
import com.dating.safety.persistence.RestrictionRepository;
import com.dating.social.application.MatchService;
import com.dating.social.persistence.SocialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records reactions and catches people who like everybody without looking (rule, not ML: see
 * {@link LikeSpamProperties}). A hit restricts likes with escalation and removes the likes of the spam window,
 * so they disappear from the receivers' "who liked me". Matches that already came out of them stay.
 */
@Service
public class LikeSpamDetector {

    public static final String LIKE_SPAM = "LIKE_SPAM";
    private static final Logger log = LoggerFactory.getLogger(LikeSpamDetector.class);

    private final BehaviorRepository behavior;
    private final RestrictionRepository restrictions;
    private final SanctionService sanctionService;
    private final SocialRepository socialRepository;
    private final MatchService matchService;
    private final RestrictionGuard guard;
    private final LikeSpamProperties properties;

    public LikeSpamDetector(BehaviorRepository behavior, RestrictionRepository restrictions,
                            SanctionService sanctionService, SocialRepository socialRepository,
                            MatchService matchService, RestrictionGuard guard, LikeSpamProperties properties) {
        this.behavior = behavior;
        this.restrictions = restrictions;
        this.sanctionService = sanctionService;
        this.socialRepository = socialRepository;
        this.matchService = matchService;
        this.guard = guard;
        this.properties = properties;
    }

    /** Called for every new reaction (repeats that change nothing are not reactions). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReaction(UUID actor, UUID target, String type) {
        behavior.recordReaction(actor, target, type);
        if (!properties.enabled() || "PASS".equals(type)) {
            return;
        }
        List<ReactionEvent> window = behavior.recentReactions(actor, properties.windowSize(),
                Instant.now().minus(properties.windowMaxAge()));
        if (!isSpam(window, properties) || guard.isRestricted(actor, RestrictionKind.LIKES)) {
            return;
        }
        int earlier = restrictions.ruleRestrictions(actor, LIKE_SPAM, RestrictionKind.LIKES,
                Instant.now().minus(properties.decay()));
        int hours = (int) Math.min((long) properties.baseHours() << Math.min(earlier, 20), properties.maxHours());
        sanctionService.restrict(actor, RestrictionKind.LIKES, hours, LIKE_SPAM, "RULE", null,
                "likes " + window.stream().filter(ReactionEvent::isLike).count() + "/" + window.size());

        List<UUID> liked = new ArrayList<>();
        for (ReactionEvent event : window) {
            if (event.isLike() && matchService.findActiveBetween(actor, event.target()).isEmpty()) {
                liked.add(event.target());
            }
        }
        int removed = socialRepository.deleteLikesGivenBy(actor, liked);
        log.info("Like spam by {}: likes restricted for {} h, {} likes removed", actor, hours, removed);
    }

    /**
     * The rule itself. {@code window} is newest first and must be full ({@code windowSize} reactions).
     */
    static boolean isSpam(List<ReactionEvent> window, LikeSpamProperties p) {
        if (window.size() < p.windowSize()) {
            return false;
        }
        long likes = window.stream().filter(ReactionEvent::isLike).count();
        if (likes < Math.ceil(p.minLikeShare() * window.size())) {
            return false;
        }
        return medianGapMillis(window) < p.maxMedianGap().toMillis() || mostlyBlind(window, p);
    }

    static long medianGapMillis(List<ReactionEvent> window) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < window.size(); i++) {
            gaps.add(Duration.between(window.get(i + 1).at(), window.get(i).at()).toMillis());
        }
        gaps.sort(null);
        return gaps.isEmpty() ? Long.MAX_VALUE : gaps.get(gaps.size() / 2);
    }

    /** Only judged when the client reported views for most likes; otherwise the speed alone decides. */
    static boolean mostlyBlind(List<ReactionEvent> window, LikeSpamProperties p) {
        List<ReactionEvent> likes = window.stream().filter(ReactionEvent::isLike).toList();
        List<ReactionEvent> seen = likes.stream().filter(e -> e.lookedMs() != null).toList();
        if (seen.size() * 2 < likes.size()) {
            return false;
        }
        long blind = seen.stream().filter(e -> !e.opened() && e.lookedMs() < p.blindDwell().toMillis()).count();
        return blind >= Math.ceil(p.minBlindShare() * seen.size());
    }

}
