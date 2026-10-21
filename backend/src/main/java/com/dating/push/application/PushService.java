package com.dating.push.application;

import com.dating.chat.application.MessageSentEvent;
import com.dating.push.domain.PushProvider;
import com.dating.push.persistence.PushDeviceRepository;
import com.dating.social.application.SocialEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Device registration and push delivery for matches, messages and likes. Pushes go out after the business
 * transaction commits, on another thread (provider calls are slow and must not hold the request or its connection).
 * A push is sent even when the app is open; the client hides it in the foreground (it also gets the SSE event).
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushDeviceRepository repository;
    private final Map<PushProvider, PushSender> senders = new EnumMap<>(PushProvider.class);
    private final TaskExecutor executor;

    public PushService(PushDeviceRepository repository, List<PushSender> senders,
                       @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.repository = repository;
        senders.forEach(sender -> this.senders.put(sender.provider(), sender));
        this.executor = executor;
    }

    @Transactional
    public void register(UUID accountId, UUID sessionId, PushProvider provider, String token) {
        repository.register(accountId, sessionId, provider, token.trim());
    }

    @Transactional
    public void unregister(UUID sessionId) {
        repository.unregister(sessionId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMatchCreated(SocialEvents.MatchCreated event) {
        PushMessage message = new PushMessage("Новый мэтч", "Симпатия взаимна — можно начать общение",
                Map.of("type", "match", "matchId", event.matchId().toString()));
        notifyLater(event.accountA(), message);
        notifyLater(event.accountB(), message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageSent(MessageSentEvent event) {
        notifyLater(event.recipientAccountId(), new PushMessage("Новое сообщение", "Вам написали в чате",
                Map.of("type", "message", "matchId", event.matchId().toString())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLikeReceived(SocialEvents.LikeReceived event) {
        notifyLater(event.targetAccountId(), event.superlike()
                ? new PushMessage("Суперлайк", "Кто-то отправил вам суперлайк", Map.of("type", "like_received", "superlike", "true"))
                : new PushMessage("Новый лайк", "Кому-то понравилась ваша анкета", Map.of("type", "like_received", "superlike", "false")));
    }

    /** Days without activity after which the profile leaves the feed (DiscoveryService.ACTIVE_WITHIN_DAYS). */
    static final int HIDDEN_AFTER_DAYS = 30;

    /** Reminds 7, 3 and 1 day before an inactive profile leaves the feed (docs/matching.md, part 4). */
    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Moscow")
    public void remindInactive() {
        for (int daysLeft : new int[]{7, 3, 1}) {
            PushMessage message = new PushMessage("Вас скоро перестанут показывать",
                    daysLeft == 1 ? "Зайдите сегодня, иначе анкета пропадёт из ленты"
                            : "Анкета пропадёт из ленты через " + daysLeft + " дн. — загляните, вас ждут",
                    Map.of("type", "inactivity_reminder"));
            for (UUID account : repository.inactiveFor(HIDDEN_AFTER_DAYS - daysLeft)) {
                deliver(account, message);
            }
        }
    }

    private void notifyLater(UUID accountId, PushMessage message) {
        try {
            executor.execute(() -> deliver(accountId, message));
        } catch (RuntimeException e) {
            log.warn("Push was not queued: {}", e.getMessage());
        }
    }

    void deliver(UUID accountId, PushMessage message) {
        try {
            for (PushDeviceRepository.Device device : repository.activeDevices(accountId)) {
                PushSender sender = senders.get(device.provider());
                if (sender != null && sender.send(device.token(), message) == PushSender.Result.INVALID_TOKEN) {
                    repository.delete(device.token());
                }
            }
        } catch (RuntimeException e) {
            // a push is best effort: it must never surface as an error anywhere
            log.warn("Push delivery failed: {}", e.getMessage());
        }
    }
}
