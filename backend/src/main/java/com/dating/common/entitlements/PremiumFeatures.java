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

    /** Go back to the last swiped profiles (up to 5 in a row). */
    public static final String UNDO_SWIPE = "UNDO_SWIPE";

    /** Bring passed profiles back at any time (without it: only when the feed ran out, once a day). */
    public static final String PASS_REFRESH = "PASS_REFRESH";

    /** Invisible mode: the profile is shown only to people its owner liked. */
    public static final String INVISIBLE = "INVISIBLE";

    private PremiumFeatures() {
    }
}
