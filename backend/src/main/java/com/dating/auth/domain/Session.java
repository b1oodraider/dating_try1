package com.dating.auth.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Session() {
    }

    public Session(UUID id, UUID accountId, String tokenHash, Instant expiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }
}
