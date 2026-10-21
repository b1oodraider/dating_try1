package com.dating.safety.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Selfie verification as a gate (docs/matching.md, part 1). Test accounts are exempt.
 *
 * @param enabled         unverified profiles are not shown to others, see little, and their likes wait
 * @param dailyViews      distinct profiles an unverified account may see per day
 * @param queuedLikeTtl   a queued like dies after this long; time under selfie review does not count
 */
@ConfigurationProperties(prefix = "app.verification-gate")
public record VerificationGateProperties(@DefaultValue("true") boolean enabled,
                                         @DefaultValue("50") int dailyViews,
                                         @DefaultValue("24h") Duration queuedLikeTtl) {
}
