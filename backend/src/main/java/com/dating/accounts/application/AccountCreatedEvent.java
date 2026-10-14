package com.dating.accounts.application;

import java.util.UUID;

/**
 * Published inside the transaction that created the account. Listeners that react to it
 * (e.g. future achievements, welcome rewards) should use @TransactionalEventListener(AFTER_COMMIT).
 */
public record AccountCreatedEvent(UUID accountId) {
}
