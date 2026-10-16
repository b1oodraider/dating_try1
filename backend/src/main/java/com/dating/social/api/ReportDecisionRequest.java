package com.dating.social.api;

import com.dating.social.domain.ReportDecision;
import com.dating.social.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param reason required for RECLASSIFIED (must differ from the reported reason), not allowed otherwise
 * @param note   optional moderator note
 */
public record ReportDecisionRequest(
        @NotNull(message = "Укажите решение") ReportDecision decision,
        ReportReason reason,
        @Size(max = 1000, message = "Заметка не должна превышать 1000 символов") String note
) {
}
