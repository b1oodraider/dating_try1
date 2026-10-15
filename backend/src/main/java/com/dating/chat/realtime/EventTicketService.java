package com.dating.chat.realtime;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.util.Hashing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time tickets for opening the event stream from a browser: EventSource cannot send an Authorization
 * header, and putting the long-lived session token into a URL would leak it into access logs, proxies and
 * browser history. A ticket is random, single-use and short-lived, and only its hash is kept (in memory:
 * fine for one instance, like the emitter registry).
 */
@Component
public class EventTicketService {

    private static final int CLEANUP_THRESHOLD = 1_000;

    public record Ticket(String ticket, Instant expiresAt) {
    }

    private record Issued(UUID accountId, Instant expiresAt) {
    }

    private final RealtimeProperties properties;
    private final Clock clock;
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();

    @Autowired
    public EventTicketService(RealtimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    EventTicketService(RealtimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Ticket issue(UUID accountId) {
        Instant now = clock.instant();
        if (issued.size() > CLEANUP_THRESHOLD) {
            issued.values().removeIf(t -> !t.expiresAt().isAfter(now));
        }
        String raw = Hashing.randomHex(32);
        Instant expiresAt = now.plus(properties.ticketTtl());
        issued.put(Hashing.sha256Hex(raw), new Issued(accountId, expiresAt));
        return new Ticket(raw, expiresAt);
    }

    /**
     * @return the account if the ticket is valid; the ticket cannot be used again either way
     */
    public Optional<UUID> consume(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank() || rawTicket.length() > 128) {
            return Optional.empty();
        }
        Issued ticket = issued.remove(Hashing.sha256Hex(rawTicket.trim()));
        if (ticket == null || !ticket.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(ticket.accountId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountDeleted(AccountDeletedEvent event) {
        issued.values().removeIf(t -> t.accountId().equals(event.accountId()));
    }
}
