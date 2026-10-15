package com.dating.auth.application;

import com.dating.auth.domain.PhoneNumbers;
import com.dating.common.util.Hashing;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * First administrators: phones from app.admin.bootstrap-phones get the ADMIN role when they log in.
 * Phones are normalized and hashed exactly like logins (same approach as {@link TestAccountPolicy}),
 * so they are never kept in plain form at runtime. Removing a phone from the config does not revoke
 * the role; that is done through the admin API.
 */
@Component
public class AdminBootstrapPolicy {

    private final Set<String> phoneHashes;

    public AdminBootstrapPolicy(AdminBootstrapProperties properties) {
        this.phoneHashes = properties.bootstrapPhones().stream()
                .map(raw -> PhoneNumbers.normalize(raw)
                        .orElseThrow(() -> new IllegalStateException("app.admin.bootstrap-phones: invalid phone " + raw)))
                .map(Hashing::sha256Hex)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isBootstrapAdmin(String phoneHash) {
        return phoneHashes.contains(phoneHash);
    }
}
