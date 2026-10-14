package com.dating.common.testing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Test accounts for QA on any environment (including production, for a closed beta).
 *
 * @param enabled    exposes /api/v1/testing and the fixed OTP code for test phones
 * @param testPhones phones that become test accounts: isolated from real users in discovery
 *                   (flag is kept even when {@code enabled} is false)
 * @param otpCode    fixed 6-digit code for test phones; no SMS is sent for them
 */
@ConfigurationProperties(prefix = "app.testing")
public record TestAccountsProperties(@DefaultValue("false") boolean enabled, List<String> testPhones, String otpCode) {

    public TestAccountsProperties {
        testPhones = testPhones == null ? List.of() : testPhones.stream().filter(p -> p != null && !p.isBlank()).toList();
        if (enabled && !testPhones.isEmpty() && (otpCode == null || !otpCode.matches("\\d{6}"))) {
            throw new IllegalStateException("app.testing.otp-code must be 6 digits when test phones are configured");
        }
    }
}
