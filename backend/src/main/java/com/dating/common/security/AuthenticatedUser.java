package com.dating.common.security;

import java.util.UUID;

public record AuthenticatedUser(
        UUID accountId,
        UUID sessionId
) {}
