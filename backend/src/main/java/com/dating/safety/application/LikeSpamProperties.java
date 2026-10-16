package com.dating.safety.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Like-spam rule: over the last {@code windowSize} reactions (not older than {@code windowMaxAge}) the share of likes
 * is at least {@code minLikeShare} AND either the median pause between reactions is below {@code maxMedianGap}, or
 * (when the client reports views) at least {@code minBlindShare} of the likes were given after looking at the profile
 * for less than {@code blindDwell} without opening it. Restriction: LIKES for baseHours · 2^(earlier ones within
 * {@code decay}), at most maxHours.
 */
@ConfigurationProperties(prefix = "app.safety.like-spam")
public record LikeSpamProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int windowSize,
        @DefaultValue("10m") Duration windowMaxAge,
        @DefaultValue("0.9") double minLikeShare,
        @DefaultValue("3s") Duration maxMedianGap,
        @DefaultValue("1s") Duration blindDwell,
        @DefaultValue("0.8") double minBlindShare,
        @DefaultValue("1") int baseHours,
        @DefaultValue("168") int maxHours,
        @DefaultValue("30d") Duration decay
) {
}
