package com.dating.social.application;

import com.dating.productevents.application.ProductEventPublisher;
import com.dating.social.persistence.SocialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Blocking hides both people from each other everywhere (feed, received likes, likes, chats) and closes
 * their match. It is silent: the blocked person just sees the chat disappear, like after an unmatch.
 */
@Service
public class BlockService {

    private final SocialRepository repository;
    private final SocialTargets targets;
    private final MatchService matchService;
    private final ProductEventPublisher productEvents;

    public BlockService(SocialRepository repository, SocialTargets targets, MatchService matchService,
                        ProductEventPublisher productEvents) {
        this.repository = repository;
        this.targets = targets;
        this.matchService = matchService;
        this.productEvents = productEvents;
    }

    /** Idempotent: blocking twice is fine. */
    @Transactional
    public void block(UUID me, UUID profileId) {
        SocialTargets.Counterpart target = targets.requireCounterpart(me, profileId);
        repository.lockPair(me, target.accountId());
        repository.insertBlock(me, target.accountId());
        matchService.closeActiveBetween(me, target.accountId(), me, MatchService.CLOSED_BY_BLOCK);
        productEvents.publish("account_blocked", Map.of());
    }
}
