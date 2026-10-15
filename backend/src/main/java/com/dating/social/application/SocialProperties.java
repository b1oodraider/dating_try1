package com.dating.social.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param passTtl        how long a "pass" hides a profile from the feed
 * @param reportsPerHour reports one account may file per hour (in-memory limiter)
 */
@ConfigurationProperties(prefix = "app.social")
public record SocialProperties(@DefaultValue("30d") Duration passTtl, @DefaultValue("20") int reportsPerHour) {

    public SocialProperties {
        if (passTtl.isNegative() || passTtl.isZero()) {
            throw new IllegalStateException("app.social.pass-ttl must be positive");
        }
        if (reportsPerHour < 1) {
            throw new IllegalStateException("app.social.reports-per-hour must be >= 1");
        }
    }
}
