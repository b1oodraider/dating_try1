package com.dating.safety.application;

import com.dating.chat.realtime.SseEmitterRegistry;
import com.dating.common.errors.ApiException;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.safety.domain.Restriction;
import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.domain.SanctionPolicy;
import com.dating.safety.persistence.RestrictionRepository;
import com.dating.social.application.MatchService;
import com.dating.social.persistence.SocialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies and lifts sanctions and keeps their effects in sync:
 * SUSPENDED → accounts.status = SUSPENDED, every like the account gave is removed (it disappears from the
 * receivers' "who liked me"), its matches are closed and its event streams cut;
 * HIDDEN / VERIFICATION_REQUIRED / SUSPENDED → profiles.visibility_status = HIDDEN (out of the feed and of
 * "who liked me", likes are kept); LIKES / MESSAGES are checked by {@link RestrictionGuard}.
 */
@Service
public class SanctionService {

    /** Profile hidden while a report that needs an urgent look is open (see sanction_policy.hold_on_reports). */
    public static final String REVIEW_HOLD = "REVIEW_HOLD";
    public static final String MANUAL = "MANUAL";

    private static final Logger log = LoggerFactory.getLogger(SanctionService.class);
    private static final Set<RestrictionKind> HIDING = Set.of(
            RestrictionKind.HIDDEN, RestrictionKind.VERIFICATION_REQUIRED, RestrictionKind.SUSPENDED);

    private final RestrictionRepository repository;
    private final SocialRepository socialRepository;
    private final MatchService matchService;
    private final SseEmitterRegistry realtime;
    private final ProductEventPublisher productEvents;

    public SanctionService(RestrictionRepository repository, SocialRepository socialRepository,
                           MatchService matchService, SseEmitterRegistry realtime, ProductEventPublisher productEvents) {
        this.repository = repository;
        this.socialRepository = socialRepository;
        this.matchService = matchService;
        this.realtime = realtime;
        this.productEvents = productEvents;
    }

    /**
     * Consequence of a confirmed report: the next step of the policy of {@code finalReason}
     * (step = earlier sanctioned violations of that reason within the policy window).
     *
     * @return restrictions created (may be empty: e.g. the first INAPPROPRIATE_CONTENT strike)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Restriction> applyReportSanction(UUID accountId, UUID reportId, String finalReason, UUID moderator) {
        repository.lockAccount(accountId);
        SanctionPolicy policy = repository.policy(finalReason)
                .orElseThrow(() -> new IllegalStateException("No sanction_policy row for " + finalReason));
        int prior = repository.sanctionedViolations(accountId, finalReason,
                Instant.now().minus(Duration.ofDays(policy.windowDays())), reportId);
        List<Restriction> created = new ArrayList<>();
        for (SanctionPolicy.StepRestriction step : policy.step(prior).restrictions()) {
            created.add(insert(accountId, step.kind(), step.hours(), finalReason, "MODERATOR", reportId, moderator, null));
        }
        repository.markSanctioned(reportId);
        sync(accountId, created);
        return created;
    }

    /** A restriction set by hand (moderator/admin) or by a rule. */
    @Transactional
    public Restriction restrict(UUID accountId, RestrictionKind kind, Integer hours, String reason, String source,
                               UUID createdBy, String note) {
        repository.lockAccount(accountId);
        Restriction restriction = insert(accountId, kind, hours, reason, source, null, createdBy, note);
        sync(accountId, List.of(restriction));
        return restriction;
    }

    /**
     * @throws ApiException 404 RESTRICTION_NOT_FOUND, 409 RESTRICTION_NOT_ACTIVE
     */
    @Transactional
    public Restriction lift(UUID restrictionId, UUID liftedBy, String note) {
        Restriction restriction = repository.find(restrictionId)
                .orElseThrow(() -> ApiException.notFound("RESTRICTION_NOT_FOUND", "Ограничение не найдено"));
        repository.lockAccount(restriction.accountId());
        if (!repository.lift(restrictionId, liftedBy, note)) {
            throw ApiException.conflict("RESTRICTION_NOT_ACTIVE", "Ограничение уже снято или истекло");
        }
        sync(restriction.accountId(), List.of());
        return repository.find(restrictionId).orElseThrow();
    }

    /**
     * Hides the profile while some open report needs an urgent look (policy.holdOnReports different reporters
     * of one reason), and shows it again when none does. Call after a report is filed or decided.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncReviewHold(UUID accountId) {
        repository.lockAccount(accountId);
        boolean needed = repository.policies().stream()
                .filter(p -> p.holdOnReports() != null)
                .anyMatch(p -> repository.openReporters(accountId, p.reason()) >= p.holdOnReports());
        List<Restriction> holds = repository.active(accountId).stream()
                .filter(r -> REVIEW_HOLD.equals(r.reason())).toList();
        if (needed && holds.isEmpty()) {
            Restriction hold = insert(accountId, RestrictionKind.HIDDEN, null, REVIEW_HOLD, "RULE", null, null, null);
            sync(accountId, List.of(hold));
        } else if (!needed && !holds.isEmpty()) {
            holds.forEach(h -> repository.lift(h.id(), null, "reports resolved"));
            sync(accountId, List.of());
        }
    }

    @Transactional(readOnly = true)
    public List<Restriction> active(UUID accountId) {
        return repository.active(accountId);
    }

    @Transactional(readOnly = true)
    public List<Restriction> history(UUID accountId) {
        return repository.history(accountId);
    }

    /** Ended restrictions stop applying by themselves; this also restores the derived state (status, visibility). */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    @Transactional
    public void expireEnded() {
        for (UUID accountId : repository.expireEnded()) {
            sync(accountId, List.of());
        }
    }

    private Restriction insert(UUID accountId, RestrictionKind kind, Integer hours, String reason, String source,
                               UUID reportId, UUID createdBy, String note) {
        productEvents.publish("restriction_applied", Map.of("kind", kind.name(), "reason", reason, "source", source));
        return repository.insert(accountId, kind, reason, source, reportId, createdBy, note, hours);
    }

    /**
     * Recomputes accounts.status and profiles.visibility_status from the active restrictions and runs the
     * one-off effects of the ones just created.
     */
    private void sync(UUID accountId, List<Restriction> created) {
        Set<RestrictionKind> kinds = kindsOf(repository.active(accountId));
        boolean suspended = kinds.contains(RestrictionKind.SUSPENDED);
        if (suspended) {
            repository.setAccountStatus(accountId, "ACTIVE", "SUSPENDED");
        } else {
            repository.setAccountStatus(accountId, "SUSPENDED", "ACTIVE");
        }
        repository.setProfileVisibility(accountId, kinds.stream().anyMatch(HIDING::contains) ? "HIDDEN" : "PUBLIC");

        if (created.stream().anyMatch(r -> r.kind() == RestrictionKind.SUSPENDED)) {
            int likes = socialRepository.deleteLikesGivenBy(accountId, null);
            matchService.closeAllOf(accountId);
            afterCommit(() -> realtime.closeAll(accountId));
            log.info("Account {} suspended: {} likes removed, matches closed", accountId, likes);
        }
    }

    private static Set<RestrictionKind> kindsOf(List<Restriction> restrictions) {
        Set<RestrictionKind> kinds = EnumSet.noneOf(RestrictionKind.class);
        restrictions.forEach(r -> kinds.add(r.kind()));
        return kinds;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
