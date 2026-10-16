package com.dating.chat.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored chat message. {@code seq} is the global ordering key (pagination cursor, unread counts);
 * it is internal, the API uses message ids.
 */
public record ChatMessage(
        UUID id,
        long seq,
        UUID matchId,
        UUID senderAccountId,
        String clientMessageId,
        MessageType type,
        String text,
        String stickerPackCode,
        String sticker,
        Instant createdAt
) {
    /** Human-readable content for moderation snapshots. */
    public String snapshot() {
        return type == MessageType.TEXT ? text : "[стикер " + stickerPackCode + "/" + sticker + "]";
    }
}
