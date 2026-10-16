package com.dating.gacha.api;

import java.util.List;
import java.util.UUID;

/**
 * Result of POST /gacha/banners/{code}/pull.
 *
 * @param replayed      true when this is a retry of an already completed request (nothing was spent again)
 * @param shardsGranted total shards for duplicates in this request
 * @param pity          the caller's counters after the pull (current values on a replay)
 * @param costBalance   remaining balance of the item the banner costs
 * @param shardBalance  current shard balance
 */
public record PullResponseDto(UUID requestId, String bannerCode, int count, boolean replayed,
                              List<PullResultDto> results, int shardsGranted, PityProgressDto pity,
                              BalanceDto costBalance, BalanceDto shardBalance) {
}
