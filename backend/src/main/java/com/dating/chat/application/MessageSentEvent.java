package com.dating.chat.application;

import java.util.UUID;

/**
 * A new chat message was stored (not published for idempotent retries). Published inside the transaction;
 * listeners that act on it (e.g. the QA auto-reply bot) should use AFTER_COMMIT.
 */
public record MessageSentEvent(UUID matchId, UUID messageId, UUID senderAccountId, UUID recipientAccountId) {
}
