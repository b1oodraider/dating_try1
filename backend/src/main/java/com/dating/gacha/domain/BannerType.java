package com.dating.gacha.domain;

/**
 * PERMANENT banners are always open (unless deactivated); LIMITED ones only inside [startsAt, endsAt).
 */
public enum BannerType {
    PERMANENT,
    LIMITED
}
