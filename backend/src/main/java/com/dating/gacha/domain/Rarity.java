package com.dating.gacha.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Rarity tiers, from the most common. Same codes as {@code catalog_items.rarity}.
 */
public enum Rarity {
    COMMON,
    RARE,
    EPIC,
    LEGENDARY;

    /** What the "EPIC or better" guarantee accepts. */
    public boolean epicOrBetter() {
        return this == EPIC || this == LEGENDARY;
    }

    public static Optional<Rarity> parse(String value) {
        return Arrays.stream(values()).filter(r -> r.name().equalsIgnoreCase(value)).findFirst();
    }
}
