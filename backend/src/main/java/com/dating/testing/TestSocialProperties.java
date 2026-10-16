package com.dating.testing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * QA chat helpers (same prefix as TestAccountsProperties; only used when app.testing.enabled=true).
 *
 * @param seedAutoReply      seed profiles answer messages from test accounts with a canned phrase
 * @param seedAutoReplyDelay how long the seed "thinks" before answering
 */
@ConfigurationProperties(prefix = "app.testing")
public record TestSocialProperties(
        @DefaultValue("false") boolean seedAutoReply,
        @DefaultValue("3s") Duration seedAutoReplyDelay
) {
}
