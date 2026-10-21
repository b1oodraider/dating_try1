package com.dating.safety.application;

import java.util.UUID;

/** A moderator approved the selfie; published inside the decision transaction. */
public record AccountVerifiedEvent(UUID accountId) {
}
