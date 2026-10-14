package com.dating.auth.application;

import com.dating.common.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    public AuthConfig(AuthProperties properties, Environment environment) {
        if (properties.mockEnabled() && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("app.auth.mock-enabled=true is forbidden with the 'prod' profile");
        }
        if (properties.mockEnabled()) {
            log.warn("MOCK OTP MODE IS ON: every phone accepts a fixed code. Never enable this outside local development.");
        }
    }

    @Bean
    public OtpSender otpSender(AuthProperties properties) {
        if (properties.mockEnabled()) {
            return new MockOtpSender();
        }
        log.error("No SMS provider is configured: OTP requests will fail with 503 until an OtpSender bean is provided.");
        return new UnavailableOtpSender();
    }

    /**
     * Dev only: the code is returned in the API response (devMockCode), nothing is sent.
     */
    static final class MockOtpSender implements OtpSender {
        @Override
        public void send(String phoneDigits, String code) {
            // intentionally no-op
        }
    }

    /**
     * Fails loudly instead of silently "sending" a code nobody receives.
     */
    static final class UnavailableOtpSender implements OtpSender {
        @Override
        public void send(String phoneDigits, String code) {
            throw ApiException.serviceUnavailable("OTP_DELIVERY_UNAVAILABLE",
                    "Отправка кода временно недоступна. Попробуйте позже");
        }
    }
}
