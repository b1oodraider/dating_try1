package com.dating.auth.application;

import com.dating.common.util.Hashing;
import org.springframework.stereotype.Component;

/**
 * Keyed hash of a normalized phone (the account key). A plain SHA-256 is reversible here: there are only
 * ~10^10 phone numbers, so a leaked table is brute-forced in minutes. Rotating the secret detaches every
 * account from its phone, so it is set once per environment.
 */
@Component
public class PhoneHasher {

    private final String secret;

    public PhoneHasher(AuthProperties properties) {
        this.secret = properties.phoneHashSecret();
    }

    /** @param normalizedPhone output of {@link com.dating.auth.domain.PhoneNumbers#normalize} */
    public String hash(String normalizedPhone) {
        return Hashing.hmacSha256Hex(secret, normalizedPhone);
    }
}
