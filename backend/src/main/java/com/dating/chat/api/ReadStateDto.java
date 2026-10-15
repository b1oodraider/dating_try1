package com.dating.chat.api;

import java.time.Instant;
import java.util.UUID;

/**
 * My read pointer after POST /matches/{id}/read, with the unread count left in this chat.
 */
public record ReadStateDto(UUID matchId, UUID lastReadMessageId, Instant lastReadAt, long unreadCount) {
}
