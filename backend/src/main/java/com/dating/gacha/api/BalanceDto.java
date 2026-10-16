package com.dating.gacha.api;

/**
 * Current total balance of one consumable (both wallet buckets).
 */
public record BalanceDto(String itemCode, long quantity) {
}
