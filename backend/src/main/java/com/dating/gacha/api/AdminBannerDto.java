package com.dating.gacha.api;

import com.dating.gacha.domain.BannerType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A banner as seen by content editors: everything that is stored, the whole pool with rates and usage.
 *
 * @param pulls pulls made on this banner (a banner with pulls can be deactivated, not deleted)
 */
public record AdminBannerDto(String code, String title, String description, BannerType type, Instant startsAt,
                             Instant endsAt, boolean active, String costItemCode, int costPerPull, int multiPullSize,
                             String pityGroup, PityRulesDto pityRules, int sortOrder, Map<String, Object> attributes,
                             BannerDto.Rates rates, List<BannerDto.Entry> pool, long pulls) {
}
