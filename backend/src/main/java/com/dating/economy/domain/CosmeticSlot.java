package com.dating.economy.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Places where an equippable cosmetic is worn; one item per slot. Collections without a slot
 * (sticker packs) are simply owned. A new cosmetic of an existing slot needs only a catalog row;
 * a new kind of slot is one constant here plus rendering in clients.
 */
public enum CosmeticSlot {
    /** Frame around the main photo in the feed and profile. Visible to others. */
    PHOTO_FRAME(true),
    /** Unique status / badge next to the name. Visible to others. */
    PROFILE_STATUS(true),
    /** Background of the user's chats. Private. */
    CHAT_WALLPAPER(false);

    private final boolean publiclyVisible;

    CosmeticSlot(boolean publiclyVisible) {
        this.publiclyVisible = publiclyVisible;
    }

    public boolean publiclyVisible() {
        return publiclyVisible;
    }

    public static Optional<CosmeticSlot> parse(String value) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(value)).findFirst();
    }
}
