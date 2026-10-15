package com.dating.gacha.api;

/**
 * @param applied     false when the idempotency key was already used (nothing was exchanged again)
 * @param tickets     tickets requested in this call
 * @param shardsSpent shards this exchange costs (tickets × rate)
 */
public record ShardExchangeResultDto(boolean applied, int tickets, long shardsSpent, BalanceDto shardBalance,
                                     BalanceDto ticketBalance) {
}
