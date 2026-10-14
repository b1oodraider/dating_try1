package com.dating.economy.api;

import com.dating.economy.domain.ItemKind;

import java.time.Instant;
import java.util.List;

/**
 * @param premiumFeatures feature codes that require premium (for paywall hints in clients)
 * @param items           owned items with a positive balance (cosmetics/sticker packs have quantity 1)
 */
public record WalletDto(Premium premium, List<String> premiumFeatures, List<Item> items) {

    public record Premium(boolean active, Instant until) {
    }

    public record Item(String code, ItemKind kind, long quantity) {
    }
}
