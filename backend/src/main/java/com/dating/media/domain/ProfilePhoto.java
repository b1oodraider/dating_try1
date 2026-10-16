package com.dating.media.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profile_photos")
public class ProfilePhoto extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "storage_key", nullable = false, unique = true, length = 100)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 30)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "content_sha256", length = 64, updatable = false)
    private String contentSha256;

    @Column(name = "dhash", updatable = false)
    private Long dhash;

    protected ProfilePhoto() {
    }

    /** Duplicate-photo fingerprints (see PhotoFingerprint); written once with the row. */
    public void setFingerprint(String contentSha256, Long dhash) {
        this.contentSha256 = contentSha256;
        this.dhash = dhash;
    }

    public ProfilePhoto(UUID id, UUID profileId, String storageKey, String mimeType, long sizeBytes, int sortOrder, boolean isPrimary) {
        this.id = id;
        this.profileId = profileId;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sortOrder = sortOrder;
        this.isPrimary = isPrimary;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public void setPrimary(boolean primary) {
        isPrimary = primary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.isPrimary = false;
    }
}
