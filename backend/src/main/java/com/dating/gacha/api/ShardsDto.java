package com.dating.gacha.api;

import com.dating.gacha.domain.Rarity;

import java.util.Map;

/**
 * Duplicate compensation: the caller's shards and the exchange terms.
 *
 * @param duplicateShards shards granted for a duplicate cosmetic of each rarity
 * @param shardsPerTicket exchange rate: this many shards → 1 ticket
 */
public record ShardsDto(BalanceDto balance, String ticketItemCode, int shardsPerTicket, int maxTicketsPerExchange,
                        Map<Rarity, Integer> duplicateShards) {
}
