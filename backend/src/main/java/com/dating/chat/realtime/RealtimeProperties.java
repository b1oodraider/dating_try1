package com.dating.chat.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Server-Sent Events (GET /api/v1/events).
 *
 * @param heartbeatInterval        "heartbeat" event period; keeps proxies from closing idle connections
 * @param ticketTtl                lifetime of a one-time connection ticket (POST /events/ticket)
 * @param emitterTimeout           a connection is closed after this time; the client reconnects with a new
 *                                 ticket, which also bounds how long a revoked session keeps streaming
 * @param maxConnectionsPerAccount the oldest connection is closed when an account opens more
 */
@ConfigurationProperties(prefix = "app.realtime")
public record RealtimeProperties(
        @DefaultValue("25s") Duration heartbeatInterval,
        @DefaultValue("60s") Duration ticketTtl,
        @DefaultValue("30m") Duration emitterTimeout,
        @DefaultValue("5") int maxConnectionsPerAccount
) {
    public RealtimeProperties {
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()
                || ticketTtl.isNegative() || ticketTtl.isZero()
                || emitterTimeout.isNegative() || emitterTimeout.isZero()
                || maxConnectionsPerAccount < 1) {
            throw new IllegalStateException("app.realtime: durations must be positive, max-connections-per-account >= 1");
        }
    }
}
