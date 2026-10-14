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
        @DefaultValue("30") int maxChallengesPerIpPerHour
) {
}
