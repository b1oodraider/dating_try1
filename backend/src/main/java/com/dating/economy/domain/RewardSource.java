package com.dating.economy.domain;

/**
 * Where a wallet change came from. Stored with every transaction for audit and analytics.
 */
public enum RewardSource {
    /** Paid subscription period (premium days). */
    SUBSCRIPTION,
    /** Periodic consumables included in premium (e.g. 5 super likes a week). */
    PREMIUM_ALLOWANCE,
    /** Achievement rewards (future achievements module). */
    ACHIEVEMENT,
    /** Gacha pulls (future gacha module). */
    GACHA,
    /** One-off purchase of items. */
    PURCHASE,
    /** Promo codes / campaigns. */
    PROMO,
    /** Manual grant by support. */
    ADMIN,
    /** Consumption by the user. */
    SPEND,
    /** Reversal after a refund. */
    REFUND
}
