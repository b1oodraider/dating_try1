package com.dating.accounts.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "phone_hash", nullable = false, unique = true, length = 64)
    private String phoneHash;

    @Column(name = "phone_masked", nullable = false, length = 30)
    private String phoneMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountStatus status;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA requirement
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

    public AccountStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Irreversibly detaches the account from the phone number. The row is kept only as a tombstone
     * (foreign keys, analytics), the same phone can register a brand-new account afterwards.
     */
    public void markDeleted() {
        this.phoneHash = "deleted:" + id;
        this.phoneMasked = "удалён";
        this.status = AccountStatus.DELETED;
        this.updatedAt = Instant.now();
    }
}
