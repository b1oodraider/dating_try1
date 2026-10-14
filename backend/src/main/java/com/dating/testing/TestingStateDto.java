package com.dating.testing;

import com.dating.achievements.api.AchievementDto;
import com.dating.economy.api.WalletDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything a tester needs to see after an action.
 *
 * @param clockOffsetDays how far the account's economy clock is moved forward
 * @param economyNow      "now" used for premium expiry and allowance periods of this account
 */
public record TestingStateDto(UUID accountId, double clockOffsetDays, Instant economyNow,
                              WalletDto wallet, List<AchievementDto> achievements) {
}
