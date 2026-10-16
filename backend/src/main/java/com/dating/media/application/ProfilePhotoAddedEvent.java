package com.dating.media.application;

import java.util.UUID;

/**
 * A photo was added to the profile. Hook for future achievements;
 * listeners should use @TransactionalEventListener(AFTER_COMMIT).
 */
public record ProfilePhotoAddedEvent(UUID accountId, UUID photoId, long photoCount) {
}
