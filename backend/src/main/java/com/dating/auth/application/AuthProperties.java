package com.dating.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("false") boolean mockEnabled,
        @DefaultValue("5m") Duration otpTtl,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30d") Duration sessionTtl,
        @DefaultValue("60s") Duration resendCooldown,
        @DefaultValue("5") int maxChallengesPerPhonePerHour,
        @DefaultValue("10") int maxChallengesPerPhonePerDay,
        @DefaultValue("30") int maxChallengesPerIpPerHour,
        String phoneHashSecret
) {
    private static final int MIN_SECRET_LENGTH = 32;

    public AuthProperties {
        if (phoneHashSecret == null || phoneHashSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "app.auth.phone-hash-secret (env APP_AUTH_PHONE_HASH_SECRET) must be set, at least "
                            + MIN_SECRET_LENGTH + " characters");
        }
    }
}
