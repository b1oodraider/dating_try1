package com.dating.economy.domain;

import java.util.Map;

/**
 * A catalog row. {@code attributes} is free-form rendering data for clients (colors, emoji, asset keys),
 * so new cosmetics of an existing slot can be added without client releases.
 *
 * @param equipSlot null for consumables and collections (sticker packs)
 * @param rarity    COMMON | RARE | EPIC | LEGENDARY, null for consumables
 */
public record CatalogItem(
        String code,
        ItemKind kind,
        String category,
        String equipSlot,
        String title,
        String rarity,
        Map<String, Object> attributes
) {
}
