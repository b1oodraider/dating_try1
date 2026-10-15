package com.dating.chat.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param lastReadMessageId the newest message the reader has seen (any message of the match)
 */
public record ReadRequest(@NotNull(message = "Укажите lastReadMessageId") UUID lastReadMessageId) {
}
