package com.dating.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthChallengeResponseDto(
        UUID challengeId,
        Instant expiresAt,
        String maskedPhone,
        String devMockCode // strictly null in production
) {}
