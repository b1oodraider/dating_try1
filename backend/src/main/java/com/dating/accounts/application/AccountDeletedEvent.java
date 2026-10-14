package com.dating.accounts.application;

import java.util.UUID;

/**
 * Published synchronously inside the deletion transaction so that every module
 * wipes its data atomically together with the account (listeners are ordered: media → profiles → auth).
 */
public record AccountDeletedEvent(UUID accountId) {
}
