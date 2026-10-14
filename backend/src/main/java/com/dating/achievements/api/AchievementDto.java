package com.dating.achievements.api;

import java.time.Instant;
import java.util.List;

public record AchievementDto(String code, String title, String description, List<RewardDto> rewards,
                             boolean completed, Instant completedAt) {

    /**
     * @param type ITEM (itemCode + quantity) or PREMIUM_DAYS (days)
     */
    public record RewardDto(String type, String itemCode, Long quantity, Integer days) {
    }
}
