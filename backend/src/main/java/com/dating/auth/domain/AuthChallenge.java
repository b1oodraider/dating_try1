package com.dating.auth.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_challenges")
public class AuthChallenge extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;

    @Column(name = "phone_masked", length = 30)
    private String phoneMasked;

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthChallenge() {
    }

    public AuthChallenge(UUID id, String phoneHash, String phoneMasked, String codeHash,
                         Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.phoneHash = phoneHash;
        this.phoneMasked = phoneMasked;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.attemptCount = 0;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public String getPhoneMasked() {
        return phoneMasked;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void registerAttempt() {
        this.attemptCount++;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void markConsumed() {
        this.consumedAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
