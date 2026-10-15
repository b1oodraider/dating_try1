package com.dating.boost.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.boost.api.BoostStatusDto;
import com.dating.boost.domain.Boost;
import com.dating.boost.domain.BoostSource;
import com.dating.boost.persistence.BoostRepository;
import com.dating.common.errors.ApiException;
import com.dating.economy.application.AllowanceService;
import com.dating.economy.application.EconomyClock;
import com.dating.economy.application.EconomyService;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Profile boost: for {@code app.boost.duration} the owner's published profile goes first in other users'
 * discovery results, among the profiles that already match their filters (see BoostRanking).
 * <p>
 * Activation spends one BOOST consumable through {@link EconomyService#spend} with a key derived from the
 * client's idempotency key, and stores the boost under the same key, so a retry returns the same boost and
 * never spends twice. Activations of one account are serialized by the profile row lock.
 * <p>
 * Times are on the owner's clock ({@link EconomyClock}): real time for real accounts, shifted time for test
 * accounts. Ranking and impression counting use the same clock in SQL (boost_owner_now), so moving a test
 * account's clock moves its boost for everyone at once. Not transactional itself: premium allowances must be
 * granted outside of a transaction first (the weekly premium boost), then the change runs in one transaction.
 */
@Service
public class BoostService {

    public static final String ITEM_CODE = "BOOST";
    static final int HISTORY_SIZE = 10;
    static final int TESTING_LIST_SIZE = 100;

    private final BoostRepository repository;
    private final BoostProperties properties;
    private final EconomyService economyService;
    private final AllowanceService allowanceService;
    private final EconomyClock clock;
    private final ProfileService profileService;
    private final ProductEventPublisher productEvents;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public BoostService(BoostRepository repository, BoostProperties properties, EconomyService economyService,
                        AllowanceService allowanceService, EconomyClock clock, ProfileService profileService,
                        ProductEventPublisher productEvents, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.economyService = economyService;
        this.allowanceService = allowanceService;
        this.clock = clock;
        this.profileService = profileService;
        this.productEvents = productEvents;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public BoostStatusDto status(UUID accountId) {
        allowanceService.ensureAllowances(accountId);
        return readOnlyTx.execute(s -> statusOf(accountId));
    }

    /**
     * @throws ApiException 400 PROFILE_NOT_PUBLISHED, 409 BOOST_ALREADY_ACTIVE, 409 INSUFFICIENT_BALANCE
     */
    public BoostStatusDto activate(UUID accountId, String idempotencyKey) {
        allowanceService.ensureAllowances(accountId);
        tx.executeWithoutResult(s -> start(accountId, BoostSource.SPEND, idempotencyKey, properties.duration()));
        return readOnlyTx.execute(s -> statusOf(accountId));
    }

    // ---------------------------------------------------------------- testing support

    /**
     * Starts a boost without spending (same rules otherwise: published profile, no stacking).
     *
     * @param duration null = app.boost.duration
     */
    public Boost startForTesting(UUID accountId, Duration duration) {
        Duration length = duration == null ? properties.duration() : duration;
        return tx.execute(s -> start(accountId, BoostSource.TESTING, null, length));
    }

    /** Ends the boost now on the owner's clock (also a boost that has not started yet after moving the clock back). */
    public int endNow(UUID accountId) {
        Integer ended = tx.execute(s -> repository.endUnfinished(accountId, clock.now(accountId)));
        return ended == null ? 0 : ended;
    }

    /** Sets impressions of the active boost, or of the latest one when none is active. */
    public void setImpressions(UUID accountId, long impressions) {
        tx.executeWithoutResult(s -> {
            Boost target = repository.findActive(accountId, clock.now(accountId))
                    .or(() -> repository.recent(accountId, 1).stream().findFirst())
                    .orElseThrow(() -> ApiException.notFound("BOOST_NOT_FOUND", "У анкеты ещё не было буста"));
            repository.setImpressions(target.id(), impressions);
        });
    }

    public List<Boost> boostsOf(UUID accountId) {
        return readOnlyTx.execute(s -> repository.recent(accountId, TESTING_LIST_SIZE));
    }

    public BoostStatusDto statusForTesting(UUID accountId) {
        return readOnlyTx.execute(s -> statusOf(accountId));
    }

    // ---------------------------------------------------------------- internals

    private Boost start(UUID accountId, BoostSource source, String idempotencyKey, Duration duration) {
        Profile profile = profileService.lockOrCreateProfile(accountId);
        if (idempotencyKey != null) {
            Optional<Boost> repeated = repository.findByKey(accountId, idempotencyKey);
            if (repeated.isPresent()) {
                return repeated.get();
            }
        }
        if (profile.getPublicationStatus() != PublicationStatus.PUBLISHED) {
            throw ApiException.badRequest("PROFILE_NOT_PUBLISHED", "Опубликуйте анкету, чтобы включить буст");
        }
        Instant now = clock.now(accountId);
        if (repository.findActive(accountId, now).isPresent()) {
            throw new ApiException("BOOST_ALREADY_ACTIVE", "Буст уже включён — дождитесь его окончания", HttpStatus.CONFLICT);
        }
        UUID boostId = UUID.randomUUID();
        if (source == BoostSource.SPEND) {
            // Throws 409 INSUFFICIENT_BALANCE and rolls everything back, so the same key works after a top-up.
            economyService.spend(accountId, ITEM_CODE, 1, boostId.toString(), "boost:" + idempotencyKey);
        }
        Boost boost = new Boost(boostId, accountId, now, now.plus(duration), 0, source);
        repository.insert(boost, idempotencyKey);
        productEvents.publish("boost_activated", Map.of("source", source.name(), "durationMinutes", duration.toMinutes()));
        return boost;
    }

    private BoostStatusDto statusOf(UUID accountId) {
        Instant now = clock.now(accountId);
        Boost current = repository.findActive(accountId, now).orElse(null);
        List<BoostStatusDto.BoostRecordDto> history = repository.recent(accountId, HISTORY_SIZE).stream()
                .map(BoostStatusDto.BoostRecordDto::from)
                .toList();
        return new BoostStatusDto(
                current != null,
                current == null ? null : current.startsAt(),
                current == null ? null : current.endsAt(),
                current == null ? 0 : current.impressions(),
                economyService.balance(accountId, ITEM_CODE),
                properties.duration().toMinutes(),
                now,
                history);
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAll(event.accountId());
    }
}
