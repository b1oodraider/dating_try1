package com.dating.economy.domain;

/**
 * Kinds of catalog items. Concrete items (super likes, frames, sticker packs, …) are rows in
 * catalog_items, so new ones need a migration, not code.
 */
public enum ItemKind {
    /** Countable, spendable: super likes, boosts, gacha tickets. */
    CONSUMABLE(true),
    /** Owned or not: profile frames, badges, themes. */
    COSMETIC(false),
    /** Owned or not: chat sticker packs. */
    STICKER_PACK(false);

    private final boolean stackable;

    ItemKind(boolean stackable) {
        this.stackable = stackable;
    }

    public boolean stackable() {
        return stackable;
    }
}
