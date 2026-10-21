package com.dating.chat.application;

import com.dating.chat.realtime.SseEmitterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «В сети / был(а) недавно» for chats: {@code accounts.last_seen_at} is written by every authenticated request,
 * at most once a minute per account. Online = an open event stream or activity within {@link #ONLINE_WINDOW}.
 */
@Service
public class PresenceService {

    static final Duration WRITE_EVERY = Duration.ofMinutes(1);
    static final Duration ONLINE_WINDOW = Duration.ofMinutes(2);

    public record Presence(Instant lastSeenAt, boolean online) {
    }

    private final JdbcClient jdbc;
    private final SseEmitterRegistry realtime;
    private final Map<UUID, Instant> lastWrite = new ConcurrentHashMap<>();

    public PresenceService(JdbcClient jdbc, SseEmitterRegistry realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    public void touch(UUID accountId) {
        Instant now = Instant.now();
        Instant previous = lastWrite.get(accountId);
        if (previous != null && previous.isAfter(now.minus(WRITE_EVERY))) {
            return;
        }
        lastWrite.put(accountId, now);
        jdbc.sql("UPDATE accounts SET last_seen_at = now() WHERE id = :id").param("id", accountId).update();
    }

    public Map<UUID, Presence> of(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> seen = new HashMap<>();
        jdbc.sql("SELECT id, last_seen_at FROM accounts WHERE id IN (:ids)").param("ids", List.copyOf(accountIds))
                .query((rs, n) -> {
                    var at = rs.getTimestamp("last_seen_at");
                    seen.put(rs.getObject("id", UUID.class), at == null ? null : at.toInstant());
                    return null;
                }).list();
        Instant onlineSince = Instant.now().minus(ONLINE_WINDOW);
        Map<UUID, Presence> result = new HashMap<>();
        for (UUID id : accountIds) {
            Instant at = seen.get(id);
            boolean online = realtime.connectionCount(id) > 0 || (at != null && at.isAfter(onlineSince));
            result.put(id, new Presence(at, online));
        }
        return result;
    }
}
