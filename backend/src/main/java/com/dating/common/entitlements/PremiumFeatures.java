package com.dating.common.entitlements;

/**
 * Codes of features that can be reserved for premium via app.economy.premium-features.
 * A feature not listed in the config is free, so moving a feature between tiers needs no code change.
 */
public final class PremiumFeatures {

    /** Discovery filters by height and goals, and the saved list of excluded goals. */
    public static final String ADVANCED_FILTERS = "ADVANCED_FILTERS";

    /** Saved exclusion words (hard filter by profile text). */
    public static final String EXCLUSION_WORDS = "EXCLUSION_WORDS";

    /** The list of people who liked me (GET /me/likes/received); the count is always free. */
    public static final String WHO_LIKED_ME = "WHO_LIKED_ME";

    private PremiumFeatures() {
    }
}
