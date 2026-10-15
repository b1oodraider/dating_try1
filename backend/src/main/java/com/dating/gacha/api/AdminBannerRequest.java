package com.dating.gacha.api;

import com.dating.gacha.domain.BannerType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * Create (POST, {@code code} required) or full replacement (PUT, code taken from the path) of a banner.
 * Omitted optional fields get defaults: active true, cost 1 GACHA_TICKET, multi-pull 10, pity group = code,
 * EPIC+ within 10, LEGENDARY at 80, no soft pity. 0 switches a guarantee off.
 */
public record AdminBannerRequest(
        String code,
        @NotBlank(message = "Укажите название") @Size(max = 100, message = "Не длиннее 100 символов") String title,
        @Size(max = 500, message = "Не длиннее 500 символов") String description,
        @NotNull(message = "Укажите тип баннера") BannerType type,
        Instant startsAt,
        Instant endsAt,
        Boolean active,
        String costItemCode,
        @Min(value = 1, message = "От 1 до 1000") @Max(value = 1000, message = "От 1 до 1000") Integer costPerPull,
        @Min(value = 2, message = "От 2 до 100") @Max(value = 100, message = "От 2 до 100") Integer multiPullSize,
        String pityGroup,
        @Min(value = 0, message = "От 0 до 1000") @Max(value = 1000, message = "От 0 до 1000") Integer epicPity,
        @Min(value = 0, message = "От 0 до 10000") @Max(value = 10000, message = "От 0 до 10000") Integer legendaryPity,
        @Min(value = 0, message = "Не меньше 0") Integer softPityStart,
        @Min(value = 0, message = "Не меньше 0") Long softPityWeightStep,
        Integer sortOrder,
        Map<String, Object> attributes
) {
}
