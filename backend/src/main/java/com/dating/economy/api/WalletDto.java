package com.dating.economy.api;

import com.dating.economy.domain.ItemKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param premiumFeatures feature codes that require premium (for paywall hints in clients)
 * @param items           owned items with a positive balance
 * @param equipped        slot -> item code of equipped cosmetics
 */
public record WalletDto(Premium premium, List<String> premiumFeatures, List<Item> items, Map<String, String> equipped) {

    public record Premium(boolean active, Instant until) {
    }

    /**
     * @param quantity         total units (cosmetics: 1)
     * @param periodicQuantity part of {@code quantity} that came from the premium allowance (spent first)
     * @param equipSlot        slot the cosmetic can be equipped into, null if not equippable
     */
    public record Item(String code, ItemKind kind, String category, String title, String rarity,
                       String equipSlot, Map<String, Object> attributes, long quantity, long periodicQuantity) {
    }
}
