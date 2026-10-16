package com.dating.economy.domain;

/**
 * Kinds of catalog items. What an item actually is lives in data: {@link CatalogItem#category()}
 * (SUPERLIKE, STICKER_PACK, PHOTO_FRAME, …), so new item types need a migration, not code.
 */
public enum ItemKind {
    /** Countable and spendable: super likes, boosts, gacha tickets. */
    CONSUMABLE(true),
    /** Owned or not: sticker packs, chat wallpapers, profile statuses, photo frames, … */
    COSMETIC(false);

    private final boolean stackable;

    ItemKind(boolean stackable) {
        this.stackable = stackable;
    }

    public boolean stackable() {
        return stackable;
    }
}
