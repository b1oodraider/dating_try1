package com.dating.profiles.application;

import java.util.UUID;

/**
 * A profile went from not published to published. Hook for future achievements;
 * listeners should use @TransactionalEventListener(AFTER_COMMIT).
 */
public record ProfilePublishedEvent(UUID accountId, UUID profileId) {
}
