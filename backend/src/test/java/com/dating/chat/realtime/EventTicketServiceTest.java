package com.dating.chat.realtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventTicketServiceTest {

    private static final RealtimeProperties PROPERTIES =
            new RealtimeProperties(Duration.ofSeconds(25), Duration.ofSeconds(60), Duration.ofMinutes(30), 5);

    @Test
    void ticketIsSingleUseAndExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EventTicketService tickets = new EventTicketService(PROPERTIES, clock);
        UUID account = UUID.randomUUID();

        EventTicketService.Ticket first = tickets.issue(account);
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"), first.expiresAt());
        assertEquals(Optional.of(account), tickets.consume(first.ticket()));
        assertEquals(Optional.empty(), tickets.consume(first.ticket()), "single use");

        EventTicketService.Ticket second = tickets.issue(account);
        clock.now = clock.now.plusSeconds(60);
        assertEquals(Optional.empty(), tickets.consume(second.ticket()), "expired");

        assertEquals(Optional.empty(), tickets.consume(null));
        assertEquals(Optional.empty(), tickets.consume(" "));
        assertEquals(Optional.empty(), tickets.consume("x".repeat(500)));
        assertNotEquals(tickets.issue(account).ticket(), tickets.issue(account).ticket());
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
