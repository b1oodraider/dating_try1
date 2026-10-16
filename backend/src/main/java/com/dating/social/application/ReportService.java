package com.dating.social.application;

import com.dating.common.errors.ApiException;
import com.dating.common.ratelimit.FixedWindowRateLimiter;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.safety.application.RiskService;
import com.dating.safety.application.SanctionService;
import com.dating.social.api.ReportRequest;
import com.dating.social.api.ReportResponseDto;
import com.dating.social.domain.ReportReason;
import com.dating.social.persistence.ModerationRepository;
import com.dating.social.persistence.SocialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores reports for moderation with status NEW; moderators work them in {@link ModerationService}.
 * A report about silence is closed at once (docs/trust-safety.md: ignoring is not a violation): the reason
 * NOT_RESPONDING, or OTHER whose comment only complains about silence while the person never wrote anything.
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
    private final RiskService riskService;
    private final ModerationRepository moderationRepository;

    static final String SILENCE_NOTE = "Молчание — не нарушение. Если общение не сложилось, мэтч можно отменить";
    private static final Pattern SILENCE = Pattern.compile(
            "(не отвеча|не ответил|игнор|молчит|не пишет|пропал)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public ReportService(SocialRepository repository, SocialTargets targets, MatchService matchService,
                         MessageLookup messageLookup, ProductEventPublisher productEvents, SocialProperties properties,
                         SanctionService sanctionService, RiskService riskService,
                         ModerationRepository moderationRepository) {
        this.moderationRepository = moderationRepository;
        this.sanctionService = sanctionService;
        this.riskService = riskService;
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
        boolean silence = request.reason() == ReportReason.NOT_RESPONDING
                || (request.reason() == ReportReason.OTHER && request.messageId() == null && comment != null
                    && SILENCE.matcher(comment).find() && !moderationRepository.wroteTo(target.accountId(), me));
        productEvents.publish("report_created", Map.of("reason", request.reason().name(),
                "withMessage", request.messageId() != null, "autoDismissed", silence));
        if (silence) {
            moderationRepository.autoDismiss(id, SILENCE_NOTE);
            return new ReportResponseDto(id, Instant.now(), "DISMISSED", SILENCE_NOTE);
        }
        sanctionService.syncReviewHold(target.accountId());
        riskService.recompute(target.accountId());
        return new ReportResponseDto(id, Instant.now(), "NEW", null);
    }
}
