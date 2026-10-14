package com.dating.auth.api;

import java.time.Instant;
import java.util.UUID;

public record AuthSessionResponseDto(
        String token,
        Instant expiresAt,
        UUID accountId
) {}
