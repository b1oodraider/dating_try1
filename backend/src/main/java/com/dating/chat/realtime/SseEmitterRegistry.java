package com.dating.chat.realtime;

import com.dating.accounts.application.AccountDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Open SSE connections by account, in memory. This is enough for the single-instance monolith; with several
 * instances an event must reach the instance holding the connection (PostgreSQL LISTEN/NOTIFY or a broker).
 * <p>
 * Events are best effort and have no replay: after a reconnect ({@code ready} event) clients refetch
 * matches and messages.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    public static final String READY = "ready";
    public static final String HEARTBEAT = "heartbeat";

    private final RealtimeProperties properties;
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats;

    public SseEmitterRegistry(RealtimeProperties properties) {
        this.properties = properties;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long period = properties.heartbeatInterval().toMillis();
        heartbeats.scheduleAtFixedRate(this::sendHeartbeats, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a connection for the account and sends the {@code ready} event.
     */
    public SseEmitter register(UUID accountId) {
        SseEmitter emitter = new SseEmitter(properties.emitterTimeout().toMillis());
        List<SseEmitter> list = emitters.compute(accountId, (id, current) -> {
            List<SseEmitter> updated = current != null ? current : new CopyOnWriteArrayList<>();
            updated.add(emitter);
            return updated;
        });
        for (SseEmitter oldest : list) { // oldest first; the new one is last
            if (list.size() <= properties.maxConnectionsPerAccount()) {
                break;
            }
            if (list.remove(oldest)) {
                oldest.complete();
            }
        }
        emitter.onCompletion(() -> remove(accountId, emitter));
        emitter.onTimeout(() -> {
            remove(accountId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(accountId, emitter));
        deliver(accountId, emitter, READY, Map.of("heartbeatSeconds", properties.heartbeatInterval().toSeconds()));
        return emitter;
    }

    /**
     * Sends an event to every open connection of the account once the current transaction commits
     * (immediately if there is none), so nobody is told about data that is then rolled back.
     */
    public void sendAfterCommit(UUID accountId, String event, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(accountId, event, payload);
                }
            });
        } else {
            send(accountId, event, payload);
        }
    }

    public void send(UUID accountId, String event, Object payload) {
        for (SseEmitter emitter : emitters.getOrDefault(accountId, List.of())) {
            deliver(accountId, emitter, event, payload);
        }
    }

    public int connectionCount(UUID accountId) {
        return emitters.getOrDefault(accountId, List.of()).size();
    }

    /** Closes every connection of the account (account deletion). */
    public void closeAll(UUID accountId) {
        List<SseEmitter> list = emitters.remove(accountId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountDeleted(AccountDeletedEvent event) {
        closeAll(event.accountId());
    }

    /**
     * Graceful shutdown would otherwise wait for every open stream to time out.
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        heartbeats.shutdownNow();
        emitters.keySet().forEach(this::closeAll);
    }

    public void sendHeartbeats() {
        Map<String, Object> payload = Map.of("at", Instant.now().toString());
        emitters.forEach((accountId, list) -> list.forEach(emitter -> deliver(accountId, emitter, HEARTBEAT, payload)));
    }

    private void deliver(UUID accountId, SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The client went away; the container reports the error to the emitter's callbacks as well.
            log.debug("SSE delivery failed ({}), dropping the connection: {}", event, e.getMessage());
            remove(accountId, emitter);
        }
    }

    private void remove(UUID accountId, SseEmitter emitter) {
        emitters.computeIfPresent(accountId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
