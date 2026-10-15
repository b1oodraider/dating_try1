package com.dating.gacha.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A banner: what a pull costs, its time window and its guarantees. The pool lives in {@link PoolEntry} rows.
 *
 * @param startsAt   null = no start limit (allowed for PERMANENT only)
 * @param endsAt     null = no end limit (allowed for PERMANENT only)
 * @param pityGroup  banners with the same group share pity counters (e.g. all limited banners)
 * @param attributes free-form rendering data for clients (art, accent colors)
 */
public record GachaBanner(
        String code,
        String title,
        String description,
        BannerType type,
        Instant startsAt,
        Instant endsAt,
        boolean active,
        String costItemCode,
        int costPerPull,
        int multiPullSize,
        String pityGroup,
        PityRules pity,
        int sortOrder,
        Map<String, Object> attributes
) {

    /**
     * Whether pulls are allowed at {@code now} (the caller's economy time). The window is [startsAt, endsAt).
     */
    public boolean openAt(Instant now) {
        return active
                && (startsAt == null || !now.isBefore(startsAt))
                && (endsAt == null || now.isBefore(endsAt));
    }
}
