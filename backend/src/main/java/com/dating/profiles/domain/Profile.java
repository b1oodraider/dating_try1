package com.dating.profiles.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "profiles")
public class Profile {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, unique = true)
    private UUID accountId;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false, length = 60)
    private String city;

    @Column(name = "about_text", length = 1000)
    private String aboutText;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private PublicationStatus publicationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_status", nullable = false, length = 20)
    private VisibilityStatus visibilityStatus;

    @Column(name = "daily_status_text", length = 200)
    private String dailyStatusText;

    @Column(name = "daily_status_expires_at")
    private Instant dailyStatusExpiresAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "profile_communication_tags",
            joinColumns = @JoinColumn(name = "profile_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<CommunicationTag> tags = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() {
    }

    public Profile(UUID id, UUID accountId, String displayName, int age, String city) {
        this.id = id;
        this.accountId = accountId;
        this.displayName = displayName;
        this.age = age;
        this.city = city;
        this.publicationStatus = PublicationStatus.DRAFT;
        this.visibilityStatus = VisibilityStatus.PUBLIC;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
        this.updatedAt = Instant.now();
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
        this.updatedAt = Instant.now();
    }

    public String getAboutText() {
        return aboutText;
    }

    public void setAboutText(String aboutText) {
        this.aboutText = aboutText;
        this.updatedAt = Instant.now();
    }

    public PublicationStatus getPublicationStatus() {
        return publicationStatus;
    }

    public void setPublicationStatus(PublicationStatus publicationStatus) {
        this.publicationStatus = publicationStatus;
        this.updatedAt = Instant.now();
    }

    public VisibilityStatus getVisibilityStatus() {
        return visibilityStatus;
    }

    public void setVisibilityStatus(VisibilityStatus visibilityStatus) {
        this.visibilityStatus = visibilityStatus;
        this.updatedAt = Instant.now();
    }

    public String getDailyStatusText() {
        if (dailyStatusExpiresAt != null && Instant.now().isAfter(dailyStatusExpiresAt)) {
            return null; // Expired 24h status
        }
        return dailyStatusText;
    }

    public Instant getDailyStatusExpiresAt() {
        return dailyStatusExpiresAt;
    }

    public void setDailyStatus(String text, Instant expiresAt) {
        this.dailyStatusText = text;
        this.dailyStatusExpiresAt = expiresAt;
        this.updatedAt = Instant.now();
    }

    public Set<CommunicationTag> getTags() {
        return tags;
    }

    public void setTags(Set<CommunicationTag> tags) {
        this.tags = tags;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
