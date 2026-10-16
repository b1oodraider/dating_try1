package com.dating.social.api;

import java.time.Instant;
import java.util.UUID;

/**
 * @param status NEW (goes to moderators) or DISMISSED (closed at once: silence is not a violation)
 * @param note   for DISMISSED, a hint to show the user; otherwise null
 */
public record ReportResponseDto(UUID id, Instant createdAt, String status, String note) {
}
