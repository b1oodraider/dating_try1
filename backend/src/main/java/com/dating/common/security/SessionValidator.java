package com.dating.common.security;

import java.util.Optional;

public interface SessionValidator {
    Optional<AuthenticatedUser> validateSession(String rawToken);
}
