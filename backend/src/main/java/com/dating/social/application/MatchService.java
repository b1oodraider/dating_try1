package com.dating.social.application;

import com.dating.common.errors.ApiException;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.social.domain.MatchInfo;
import com.dating.social.persistence.SocialRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Matches: created by a mutual like, closed by unmatch / block / account deletion.
 * The chat module reads them through this service (who may write to whom).
 */
@Service
public class MatchService {

    static final String CLOSED_BY_UNMATCH = "UNMATCH";
    static final String CLOSED_BY_BLOCK = "BLOCK";

    private final SocialRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductEventPublisher productEvents;

    public MatchService(SocialRepository repository, ApplicationEventPublisher eventPublisher,
                        ProductEventPublisher productEvents) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.productEvents = productEvents;
    }

    /**
     * Creates the active match of the pair exactly once (the partial unique index decides between
     * concurrent callers) and returns its id; returns the existing one if the pair is already matched.
     */
    @Transactional
    public UUID createOrGet(UUID a, UUID b) {
        UUID id = UUID.randomUUID();
        if (repository.insertMatch(id, a, b)) {
            eventPublisher.publishEvent(new SocialEvents.MatchCreated(id, a, b));
            productEvents.publish("match_created", Map.of());
            return id;
        }
        return repository.findActiveMatchBetween(a, b).map(MatchInfo::id)
                .orElseThrow(() -> new IllegalStateException("Active match must exist after a conflicting insert"));
    }

    @Transactional(readOnly = true)
    public Optional<MatchInfo> findActiveBetween(UUID a, UUID b) {
        return repository.findActiveMatchBetween(a, b);
    }

    /**
     * @return the match if it is active and the account takes part in it
     */
    @Transactional(readOnly = true)
    public Optional<MatchInfo> findActive(UUID matchId, UUID participant) {
        return repository.findMatch(matchId).filter(MatchInfo::active).filter(m -> m.involves(participant));
    }

    /**
     * Same as {@link #findActive} but 404 MATCH_NOT_FOUND otherwise (closed matches are hidden from both).
     */
    @Transactional(readOnly = true)
    public MatchInfo requireActive(UUID matchId, UUID participant) {
        return findActive(matchId, participant).orElseThrow(MatchService::matchNotFound);
    }

    /**
     * @return true if the account takes part in the match, active or closed (reports about old chats)
     */
    @Transactional(readOnly = true)
    public boolean isParticipant(UUID matchId, UUID accountId) {
        return repository.findMatch(matchId).filter(m -> m.involves(accountId)).isPresent();
    }

    /**
     * Active matches whose other side is an active account, newest first.
     */
    @Transactional(readOnly = true)
    public List<MatchInfo> activeMatchesOf(UUID accountId) {
        return repository.activeMatchesOf(accountId);
    }

    /**
     * Unmatch: hides the chat for both and blocks new messages. Likes are kept, so the two people
     * do not meet again in the feed.
     */
    @Transactional
    public void unmatch(UUID me, UUID matchId) {
        MatchInfo match = requireActive(matchId, me);
        close(match, me, CLOSED_BY_UNMATCH);
        productEvents.publish("match_unmatched", Map.of());
    }

    void closeActiveBetween(UUID a, UUID b, UUID closedBy, String reason) {
        repository.findActiveMatchBetween(a, b).ifPresent(match -> close(match, closedBy, reason));
    }

    void close(MatchInfo match, UUID closedBy, String reason) {
        if (repository.closeMatch(match.id(), closedBy, reason)) {
            eventPublisher.publishEvent(new SocialEvents.MatchClosed(match.id(), match.accountLow(), match.accountHigh()));
        }
    }

    static ApiException matchNotFound() {
        return ApiException.notFound("MATCH_NOT_FOUND", "Мэтч не найден");
    }
}
