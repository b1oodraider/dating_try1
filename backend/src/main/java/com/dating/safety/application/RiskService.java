package com.dating.safety.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.media.application.ProfilePhotoAddedEvent;
import com.dating.safety.persistence.BehaviorRepository;
import com.dating.safety.persistence.RiskRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bot / fake-account risk: transparent rules with weights, summed into a 0..100 score. The score only orders
 * the moderators' queue and gives context; it never punishes by itself (docs/trust-safety.md, principle 1).
 */
@Service
public class RiskService {

    /** One rule that fired: why, how much it adds, and the number behind it. */
    public record Signal(String code, int weight, String detail) {
    }

    public record Risk(UUID accountId, int score, String level, List<Signal> signals, Instant computedAt) {
    }

    public record QueueItem(UUID accountId, UUID profileId, String displayName, int score, String level,
                            JsonNode signals, Instant computedAt) {
    }

    public record QueuePage(List<QueueItem> items, int page, int limit, long totalElements) {
    }

    static final int MEDIUM = 30;
    static final int HIGH = 60;
    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskRepository repository;
    private final BehaviorRepository behavior;
    private final RoleAccess roleAccess;
    private final JsonMapper jsonMapper;

    public RiskService(RiskRepository repository, BehaviorRepository behavior, RoleAccess roleAccess,
                       JsonMapper jsonMapper) {
        this.repository = repository;
        this.behavior = behavior;
        this.roleAccess = roleAccess;
        this.jsonMapper = jsonMapper;
    }

    /** Remembers where a new account came from (a keyed hash of the address, never the address itself). */
    @Transactional
    public void onSignup(UUID accountId, String addressHash) {
        repository.recordSignup(accountId, addressHash);
        recompute(accountId);
    }

    @Transactional
    public Risk recompute(UUID accountId) {
        Instant now = Instant.now();
        List<Signal> signals = new ArrayList<>();

        int sameAddress = repository.signupsFromSameAddress(accountId);
        if (sameAddress >= 3) {
            signals.add(new Signal("SIGNUP_BURST", sameAddress >= 6 ? 35 : 25,
                    sameAddress + " аккаунтов с одного адреса за сутки"));
        }
        int samePhoto = repository.accountsWithSamePhoto(accountId);
        if (samePhoto > 0) {
            signals.add(new Signal("DUPLICATE_PHOTO", 30, "то же фото у других аккаунтов: " + samePhoto));
        }
        int sameAbout = repository.profilesWithSameAbout(accountId);
        if (sameAbout > 0) {
            signals.add(new Signal("DUPLICATE_ABOUT", 20, "тот же текст «о себе» у других анкет: " + sameAbout));
        }
        if (repository.contactsInProfile(accountId)) {
            signals.add(new Signal("CONTACTS_IN_PROFILE", 15, "ссылки или контакты в анкете"));
        }
        Instant week = now.minus(Duration.ofDays(7));
        int sameText = repository.sameTextToChats(accountId, week);
        if (sameText >= 3) {
            signals.add(new Signal("SAME_MESSAGE_TO_MANY", 25, "один и тот же текст в " + sameText + " чатах за неделю"));
        }
        int contactChats = repository.chatsWithContacts(accountId, week);
        if (contactChats >= 2) {
            signals.add(new Signal("CONTACTS_IN_MESSAGES", 20, "контакты или ссылки в " + contactChats + " чатах за неделю"));
        }
        int likeSpam = repository.ruleRestrictions(accountId, LikeSpamDetector.LIKE_SPAM, now.minus(Duration.ofDays(30)));
        if (likeSpam > 0) {
            signals.add(new Signal("LIKE_SPAM", Math.min(20, 10 * likeSpam), "спам-лайки за 30 дней: " + likeSpam));
        }
        int botMessages = repository.ruleRestrictions(accountId, BotRules.BOT_MESSAGES, now.minus(Duration.ofDays(30)));
        if (botMessages > 0) {
            signals.add(new Signal("BOT_MESSAGES", 25, "рассылка одинаковых сообщений с контактами"));
        }
        Instant month = now.minus(Duration.ofDays(30));
        int reporters = repository.reporters(accountId, month, null);
        if (reporters >= 2) {
            signals.add(new Signal("REPORTED_BY_MANY", reporters >= 5 ? 30 : 15, "жалобы от разных людей за 30 дней: " + reporters));
        }
        int fakeReporters = repository.reporters(accountId, month, List.of("FAKE_PROFILE", "SCAM", "SPAM"));
        if (fakeReporters > 0) {
            signals.add(new Signal("REPORTED_AS_FAKE", 10, "жалобы «фейк / скам / спам»: " + fakeReporters));
        }
        if (!signals.isEmpty() && repository.accountCreatedAt(accountId)
                .filter(created -> created.isAfter(now.minus(Duration.ofDays(3)))).isPresent()) {
            signals.add(new Signal("NEW_ACCOUNT", 5, "аккаунту меньше 3 дней"));
        }

        int score = Math.min(100, signals.stream().mapToInt(Signal::weight).sum());
        String level = score >= HIGH ? "HIGH" : score >= MEDIUM ? "MEDIUM" : "LOW";
        repository.save(accountId, score, level, jsonMapper.writeValueAsString(signals));
        return new Risk(accountId, score, level, signals, now);
    }

    /**
     * Moderators: the account's risk, recomputed now.
     *
     * @throws ApiException 404 ACCOUNT_NOT_FOUND
     */
    @Transactional
    public Risk riskFor(UUID actor, UUID accountId) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
        if (repository.accountCreatedAt(accountId).isEmpty()) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Аккаунт не найден");
        }
        return recompute(accountId);
    }

    /** Moderators: active accounts at or above the score, riskiest first. */
    @Transactional(readOnly = true)
    public QueuePage queue(UUID actor, Integer minScore, int page, int limit) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
        int min = minScore == null ? MEDIUM : Math.max(0, Math.min(100, minScore));
        int safeLimit = limit <= 0 || limit > 100 ? 20 : limit;
        int safePage = Math.max(page, 0);
        List<QueueItem> items = repository.queue(min, safeLimit, safePage * safeLimit).stream()
                .map(r -> new QueueItem(r.accountId(), r.profileId(), r.displayName(), r.score(), r.level(),
                        jsonMapper.readTree(r.signalsJson()), r.computedAt()))
                .toList();
        return new QueuePage(items, safePage, safeLimit, repository.queueSize(min));
    }

    @EventListener
    public void onPhotoAdded(ProfilePhotoAddedEvent event) {
        recompute(event.accountId());
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAccountData(event.accountId());
    }

    /** shortcut: one pass per account active in 30 days; move to set-based SQL when the run gets slow. */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void recomputeActive() {
        for (UUID accountId : behavior.activeSince(LocalDate.now(ZoneOffset.UTC).minusDays(30))) {
            try {
                recompute(accountId);
            } catch (RuntimeException e) {
                log.warn("Risk of {} was not computed: {}", accountId, e.getMessage());
            }
        }
    }
}
