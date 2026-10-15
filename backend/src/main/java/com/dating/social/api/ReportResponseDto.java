package com.dating.social.api;

import java.time.Instant;
import java.util.UUID;

public record ReportResponseDto(UUID id, Instant createdAt) {
}
