package com.dating.social.api;

import com.dating.social.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param messageId optional: a message of the reported person in a chat with me
 * @param comment   optional free text for the moderator
 */
public record ReportRequest(
        @NotNull(message = "Укажите анкету") UUID profileId,
        UUID messageId,
        @NotNull(message = "Укажите причину жалобы") ReportReason reason,
        @Size(max = 1000, message = "Комментарий не должен превышать 1000 символов") String comment
) {
}
