package com.dating.auth.application;

/**
 * Delivers a one-time code to the user's phone. Plug a real SMS provider in by declaring
 * an {@code OtpSender} bean in place of the one from {@link AuthConfig}.
 */
public interface OtpSender {

    /**
     * @param phoneDigits normalized phone, digits only with country code
     * @param code        plain one-time code; must never be logged by implementations
     */
    void send(String phoneDigits, String code);
}
