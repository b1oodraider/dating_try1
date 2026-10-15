package com.dating.economy.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Time-limited catalog items: an item can only be acquired by "shop-like" sources inside
 * {@code [availableFrom, availableUntil)} (either bound may be open). The window is about getting
 * the item, never about having it: owned items stay owned and equippable after the window closes.
 *
 * @param availableFrom  inclusive, null = no start
 * @param availableUntil exclusive, null = no end
 */
public record ItemAvailability(Instant availableFrom, Instant availableUntil) {

    /**
     * Sources that respect the window: the user picks the item from an offer (shop, promo campaign).
     * <ul>
     *   <li>GACHA ignores it here: the banner/pool has its own window and checks it before the pull is paid.</li>
     *   <li>ACHIEVEMENT, SUBSCRIPTION, PREMIUM_ALLOWANCE: the reward was configured by staff and already earned.</li>
     *   <li>ADMIN, TESTING: manual grants (support, contest prizes, QA).</li>
     * </ul>
     */
    private static final Set<RewardSource> WINDOW_CHECKED = EnumSet.of(RewardSource.PURCHASE, RewardSource.PROMO);

    public static final ItemAvailability ALWAYS = new ItemAvailability(null, null);

    public static boolean appliesTo(RewardSource source) {
        return WINDOW_CHECKED.contains(source);
    }

    public boolean isAvailableAt(Instant now) {
        return (availableFrom == null || !now.isBefore(availableFrom))
                && (availableUntil == null || now.isBefore(availableUntil));
    }

    public boolean isLimited() {
        return availableFrom != null || availableUntil != null;
    }
}
