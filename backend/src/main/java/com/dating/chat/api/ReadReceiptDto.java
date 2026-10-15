package com.dating.chat.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code read} real-time event: the other person has read the chat up to this message.
 */
public record ReadReceiptDto(UUID matchId, UUID lastReadMessageId, Instant lastReadAt) {
}
