package com.dating.social.application;

import com.dating.common.errors.ApiException;
import com.dating.common.ratelimit.FixedWindowRateLimiter;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.safety.application.SanctionService;
import com.dating.social.api.ReportRequest;
import com.dating.social.api.ReportResponseDto;
import com.dating.social.persistence.SocialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Stores reports for moderation with status NEW; moderators work them in {@link ModerationService}.
 */
@Service
public class ReportService {

    private final SocialRepository repository;
    private final SocialTargets targets;
    private final MatchService matchService;
    private final MessageLookup messageLookup;
    private final ProductEventPublisher productEvents;
    private final FixedWindowRateLimiter limiter;
    private final SanctionService sanctionService;

    public ReportService(SocialRepository repository, SocialTargets targets, MatchService matchService,
                         MessageLookup messageLookup, ProductEventPublisher productEvents, SocialProperties properties,
                         SanctionService sanctionService) {
        this.sanctionService = sanctionService;
        this.repository = repository;
        this.targets = targets;
        this.matchService = matchService;
        this.messageLookup = messageLookup;
        this.productEvents = productEvents;
        this.limiter = new FixedWindowRateLimiter(properties.reportsPerHour(), Duration.ofHours(1));
    }

    /**
     * @throws ApiException 404 PROFILE_NOT_FOUND / MESSAGE_NOT_FOUND (the message must be from the reported
     *                      person, in a chat I take part in), 429 RATE_LIMITED
     */
    @Transactional
    public ReportResponseDto report(UUID me, ReportRequest request) {
        long wait = limiter.tryAcquire(me.toString());
        if (wait > 0) {
            throw ApiException.tooManyRequests("Слишком много жалоб. Попробуйте позже", wait);
        }
        SocialTargets.Counterpart target = targets.requireCounterpart(me, request.profileId());
        String snapshot = null;
        if (request.messageId() != null) {
            MessageLookup.MessageRef message = messageLookup.find(request.messageId())
                    .filter(m -> m.senderAccountId().equals(target.accountId()))
                    .filter(m -> matchService.isParticipant(m.matchId(), me))
                    .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Сообщение не найдено"));
            snapshot = message.snapshot();
        }
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().trim();
        UUID id = UUID.randomUUID();
        repository.insertReport(id, me, target.accountId(), target.profileId(), request.messageId(), snapshot,
                request.reason(), comment);
        sanctionService.syncReviewHold(target.accountId());
        productEvents.publish("report_created", Map.of("reason", request.reason().name(),
                "withMessage", request.messageId() != null));
        return new ReportResponseDto(id, Instant.now());
    }
}
