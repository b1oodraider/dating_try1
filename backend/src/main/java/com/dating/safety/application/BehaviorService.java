package com.dating.safety.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.accounts.application.AccountService;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.safety.persistence.BehaviorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behavioral data (docs/trust-safety.md, block D): feed impressions, client views, active days, and per-account
 * metrics ("ghosts", "answers in one word", "likes everybody"...) recomputed nightly. Collected now so a ranking
 * model has history later; moderators see the metrics as context. Raw data lives 90 days.
 */
@Service
public class BehaviorService {

    public record Metrics(UUID accountId, Instant computedAt, JsonNode metrics) {
    }

    private static final Logger log = LoggerFactory.getLogger(BehaviorService.class);
    private static final Duration RETENTION = Duration.ofDays(90);

    private final BehaviorRepository repository;
    private final RoleAccess roleAccess;
    private final JsonMapper jsonMapper;
    private final AccountService accountService;
    /** account:day already written by this instance, so an active day costs one insert, not one per request. */
    private final Set<String> seenToday = ConcurrentHashMap.newKeySet();
    private volatile LocalDate seenDay = LocalDate.now(ZoneOffset.UTC);

    public BehaviorService(BehaviorRepository repository, RoleAccess roleAccess, JsonMapper jsonMapper,
                           AccountService accountService) {
        this.accountService = accountService;
        this.repository = repository;
        this.roleAccess = roleAccess;
        this.jsonMapper = jsonMapper;
    }

    /** Profiles of a served feed page (called inside the feed transaction). */
    public void recordImpressions(UUID viewer, Collection<UUID> shownAccounts) {
        repository.recordImpressions(viewer, shownAccounts);
    }

    /** Distinct profiles served to the viewer since midnight (Moscow time). */
    public int profilesShownToday(UUID viewer) {
        return repository.distinctShownSince(viewer,
                LocalDate.now(MOSCOW).atStartOfDay(MOSCOW).toInstant());
    }

    private static final java.time.ZoneId MOSCOW = java.time.ZoneId.of("Europe/Moscow");

    @Transactional
    public void recordViews(UUID viewer, List<BehaviorRepository.View> views) {
        repository.recordViews(viewer, views);
    }

    /** Called for every authenticated request; writes at most once per account and day. */
    @Transactional
    public void touch(UUID accountId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(seenDay)) {
            seenToday.clear();
            seenDay = today;
        }
        if (seenToday.add(accountId + ":" + today)) {
            repository.recordActiveDay(accountId, today);
        }
    }

    /**
     * Moderators: the account's metrics, recomputed now.
     *
     * @throws com.dating.common.errors.ApiException 404 ACCOUNT_NOT_FOUND
     */
    @Transactional
    public Metrics metricsFor(UUID actor, UUID accountId) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
        accountService.getAccountById(accountId);
        String json = repository.computeMetrics(accountId);
        return new Metrics(accountId, Instant.now(), jsonMapper.readTree(json));
    }

    /**
     * shortcut: one statement per account active in the last 90 days, fine for tens of thousands of accounts;
     * switch to one set-based statement (or a warehouse) when the nightly run gets slow.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void recomputeAll() {
        List<UUID> accounts = repository.activeSince(LocalDate.now(ZoneOffset.UTC).minusDays(RETENTION.toDays()));
        int failed = 0;
        for (UUID accountId : accounts) {
            try {
                repository.computeMetrics(accountId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Behavior metrics of {} were not computed: {}", accountId, e.getMessage());
            }
        }
        log.info("Behavior metrics recomputed for {} accounts ({} failed)", accounts.size() - failed, failed);
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
    @Transactional
    public void dropOldBehavior() {
        repository.deleteOlderThan(Instant.now().minus(RETENTION));
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAccountData(event.accountId());
    }
}
