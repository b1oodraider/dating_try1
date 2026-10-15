package com.dating.economy.api;

import com.dating.economy.domain.CatalogEntry;
import com.dating.economy.domain.ItemKind;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Public catalog entry (test panel, gacha UI, shop).
 *
 * @param availableNow whether the item can be acquired from offers right now (see ItemAvailability)
 * @param author       null if the item has no author
 */
public record CatalogItemDto(String code, ItemKind kind, String category, String equipSlot, String rarity,
                             String title, Map<String, Object> attributes, Instant availableFrom,
                             Instant availableUntil, boolean availableNow, AuthorRef author) {

    public record AuthorRef(UUID id, String displayName) {
    }

    public static CatalogItemDto from(CatalogEntry entry, Instant now) {
        return new CatalogItemDto(entry.code(), entry.kind(), entry.category(), entry.equipSlot(), entry.rarity(),
                entry.title(), entry.attributes(), entry.availableFrom(), entry.availableUntil(),
                entry.availability().isAvailableAt(now),
                entry.authorId() == null ? null : new AuthorRef(entry.authorId(), entry.authorName()));
    }
}
