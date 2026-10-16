package com.dating.social.api;

import com.dating.social.domain.ReportDecision;
import com.dating.social.domain.ReportReason;

import java.time.Instant;
import java.util.UUID;

/**
 * A report as the moderator sees it.
 *
 * @param reason                       what the reporter chose
 * @param finalReason                  confirmed violation (ACCEPTED: = reason, RECLASSIFIED: differs, else null)
 * @param reportedDisplayName          null when the profile is gone (e.g. the account was deleted)
 * @param reportedConfirmedViolations  reports against the same account that moderators confirmed
 * @param sanctioned                   the sanction policy was applied for this report
 */
public record ModerationReportDto(
        UUID id,
        String status,
        ReportReason reason,
        ReportReason finalReason,
        ReportDecision decision,
        boolean sanctioned,
        String comment,
        UUID messageId,
        String messageSnapshot,
        UUID reporterAccountId,
        UUID reportedAccountId,
        UUID reportedProfileId,
        String reportedDisplayName,
        long reportedConfirmedViolations,
        Instant createdAt,
        UUID moderatorAccountId,
        String moderatorNote,
        Instant decidedAt
) {
}
