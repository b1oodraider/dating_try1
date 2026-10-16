package com.dating.economy.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Author of catalog items (contest winner, artist, collaboration). Sales accounting and payouts
 * per author are not implemented yet; the ledger already records every grant by item code.
 *
 * @param accountId           linked app account, null for external authors
 * @param revenueSharePercent share of sales of the author's items, 0..100
 * @param contactNote         private note for staff (how to reach, contract), never shown publicly
 * @param itemCount           catalog items attributed to the author
 */
public record Author(UUID id, String displayName, UUID accountId, int revenueSharePercent, String contactNote,
                     Instant createdAt, Instant updatedAt, long itemCount) {
}
