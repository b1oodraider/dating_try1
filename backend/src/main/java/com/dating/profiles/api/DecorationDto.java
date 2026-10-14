package com.dating.profiles.api;

import java.util.Map;

/**
 * An equipped cosmetic as other users see it. Clients render it generically from {@code attributes}
 * (e.g. borderColor/glow for PHOTO_FRAME, emoji/label/color for PROFILE_STATUS).
 */
public record DecorationDto(String code, String title, String rarity, Map<String, Object> attributes) {
}
