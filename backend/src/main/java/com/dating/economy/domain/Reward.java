package com.dating.economy.domain;

/**
 * One line of a grant. Subscriptions, achievements, gacha and promos all produce lists of rewards
 * and go through the same EconomyService.grant(...).
 */
public sealed interface Reward {

    record Item(String itemCode, long quantity) implements Reward {
        public Item {
            if (itemCode == null || itemCode.isBlank()) {
                throw new IllegalArgumentException("itemCode is required");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    record PremiumDays(int days) implements Reward {
        public PremiumDays {
            if (days <= 0 || days > 3650) {
                throw new IllegalArgumentException("days must be in 1..3650");
            }
        }
    }
}
