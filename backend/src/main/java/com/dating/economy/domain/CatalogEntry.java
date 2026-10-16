package com.dating.economy.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Full catalog row for the admin API and the public catalog (CatalogItem is the slim runtime view).
 *
 * @param authorId   author who gets a share of sales, null if none
 * @param authorName author's display name (null without author)
 */
public record CatalogEntry(
        String code,
        ItemKind kind,
        String category,
        String equipSlot,
        String title,
        String rarity,
        Map<String, Object> attributes,
        boolean active,
        Instant availableFrom,
        Instant availableUntil,
        UUID authorId,
        String authorName,
        Instant createdAt,
        Instant updatedAt
) {
    public ItemAvailability availability() {
        return new ItemAvailability(availableFrom, availableUntil);
    }
}
