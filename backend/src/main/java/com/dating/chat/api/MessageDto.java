package com.dating.chat.api;

import com.dating.chat.domain.ChatMessage;
import com.dating.chat.domain.MessageType;

import java.time.Instant;
import java.util.UUID;

/**
 * A chat message as seen by one participant.
 *
 * @param mine            sent by the viewer
 * @param clientMessageId the sender's idempotency key; only for the viewer's own messages (null otherwise)
 * @param text            TEXT messages only
 * @param stickerPackCode STICKER messages only (catalog code of the pack, e.g. STICKERS_STUB_CATS)
 * @param sticker         STICKER messages only (code from the pack's attributes.stickers)
 * @param datePlanId      DATE_PLAN messages only: the plan (GET /date-plans/{id}); text is its one-line summary
 * @param replyTo         the message this one answers (null when none or it is gone)
 */
public record MessageDto(
        UUID id,
        UUID matchId,
        boolean mine,
        MessageType type,
        String text,
        String stickerPackCode,
        String sticker,
        String clientMessageId,
        Instant createdAt,
        UUID datePlanId,
        ReplyDto replyTo
) {
    /**
     * @param mine the answered message is the viewer's
     * @param text TEXT and DATE_PLAN only (STICKER: null)
     */
    public record ReplyDto(UUID id, boolean mine, MessageType type, String text) {
        public ReplyDto flipped() {
            return new ReplyDto(id, !mine, type, text);
        }
    }

    public static MessageDto of(ChatMessage message, UUID viewerAccountId) {
        boolean mine = message.senderAccountId().equals(viewerAccountId);
        ChatMessage.Reply reply = message.replyTo();
        return new MessageDto(message.id(), message.matchId(), mine, message.type(), message.text(),
                message.stickerPackCode(), message.sticker(), mine ? message.clientMessageId() : null,
                message.createdAt(), message.datePlanId(),
                reply == null ? null : new ReplyDto(reply.id(), reply.senderAccountId().equals(viewerAccountId),
                        reply.type(), reply.text()));
    }
}
