package com.dating.boost.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param duration    how long one boost lasts (the BOOST catalog attribute durationMinutes is only a display hint)
 * @param publicBadge show others that a profile is boosted (PublicProfile.boosted); off by default for fairness
 */
@ConfigurationProperties(prefix = "app.boost")
public record BoostProperties(Duration duration, boolean publicBadge) {

    public BoostProperties {
        duration = duration == null ? Duration.ofMinutes(30) : duration;
        if (duration.compareTo(Duration.ofMinutes(1)) < 0 || duration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalStateException("app.boost.duration must be between 1 minute and 24 hours");
        }
    }
}
