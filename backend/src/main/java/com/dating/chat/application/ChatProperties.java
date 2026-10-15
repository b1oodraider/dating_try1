package com.dating.chat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param messagesPerMinute messages one account may send per minute (in-memory limiter, like OTP limits);
 *                          retries of an already stored message are not counted
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(@DefaultValue("30") int messagesPerMinute) {

    public ChatProperties {
        if (messagesPerMinute < 1) {
            throw new IllegalStateException("app.chat.messages-per-minute must be >= 1");
        }
    }
}
