package com.dating.auth.application;

import com.dating.auth.domain.PhoneNumbers;
import com.dating.common.testing.TestAccountsProperties;
import com.dating.common.util.Hashing;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which phones are test accounts. Configured phones are normalized and hashed once,
 * so they are matched the same way as real logins and never kept in plain form at runtime.
 */
@Component
public class TestAccountPolicy {

    private final TestAccountsProperties properties;
    private final Set<String> testPhoneHashes;

    public TestAccountPolicy(TestAccountsProperties properties) {
        this.properties = properties;
        this.testPhoneHashes = properties.testPhones().stream()
                .map(raw -> PhoneNumbers.normalize(raw)
                        .orElseThrow(() -> new IllegalStateException("app.testing.test-phones: invalid phone " + raw)))
                .map(Hashing::sha256Hex)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isTestPhoneHash(String phoneHash) {
        return testPhoneHashes.contains(phoneHash);
    }

    /**
     * Fixed code for test phones when the testing mode is on (no SMS is sent for them).
     */
    public boolean usesFixedOtp(String phoneHash) {
        return properties.enabled() && isTestPhoneHash(phoneHash);
    }

    public String fixedOtpCode() {
        return properties.otpCode();
    }
}
