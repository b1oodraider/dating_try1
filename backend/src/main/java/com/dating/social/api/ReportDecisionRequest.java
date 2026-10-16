package com.dating.social.api;

import com.dating.social.domain.ReportDecision;
import com.dating.social.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param reason            required for RECLASSIFIED (must differ from the reported reason), not allowed otherwise
 * @param note              optional moderator note
 * @param sanction          apply the sanction policy of the final reason (default true); false for a duplicate
 *                          report about a violation that was already sanctioned
 * @param removePhotoIds    photos of the reported profile to delete (ACCEPTED / RECLASSIFIED only)
 * @param clearAboutText    delete the reported profile's "about me"
 * @param clearDailyStatus  delete the reported profile's daily status
 */
public record ReportDecisionRequest(
        @NotNull(message = "Укажите решение") ReportDecision decision,
        ReportReason reason,
        @Size(max = 1000, message = "Заметка не должна превышать 1000 символов") String note,
        Boolean sanction,
        @Size(max = 6, message = "Не больше 6 фотографий") List<UUID> removePhotoIds,
        Boolean clearAboutText,
        Boolean clearDailyStatus
) {
    public boolean applySanction() {
        return sanction == null || sanction;
    }

    public boolean clearsAboutText() {
        return Boolean.TRUE.equals(clearAboutText);
    }

    public boolean clearsDailyStatus() {
        return Boolean.TRUE.equals(clearDailyStatus);
    }

    public boolean removesContent() {
        return (removePhotoIds != null && !removePhotoIds.isEmpty()) || clearsAboutText() || clearsDailyStatus();
    }
}
