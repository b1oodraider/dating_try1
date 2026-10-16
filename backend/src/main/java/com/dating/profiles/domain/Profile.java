package com.dating.profiles.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "profiles")
public class Profile extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, unique = true)
    private UUID accountId;

    // Nullable until the user fills them in: no fake defaults like "age 18, Moscow".
    @Column(name = "display_name", length = 50)
    private String displayName;

    @Column(name = "age")
    private Integer age;

    @Column(name = "city", length = 60)
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

    @Column(name = "height_cm")
    private Integer heightCm;

    /** Set by the verification module (manual selfie check); JPA never writes it. */
    @Column(name = "verified_at", insertable = false, updatable = false)
    private Instant verifiedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "profile_goals",
            joinColumns = @JoinColumn(name = "profile_id"),
            inverseJoinColumns = @JoinColumn(name = "goal_code")
    )
    private Set<RelationshipGoal> goals = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() {
    }

    public Profile(UUID id, UUID accountId) {
        this.id = id;
        this.accountId = accountId;
        this.publicationStatus = PublicationStatus.DRAFT;
        this.visibilityStatus = VisibilityStatus.PUBLIC;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @Override
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
        touch();
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
        touch();
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
        touch();
    }

    public String getAboutText() {
        return aboutText;
    }

    public void setAboutText(String aboutText) {
        this.aboutText = aboutText;
        touch();
    }

    public PublicationStatus getPublicationStatus() {
        return publicationStatus;
    }

    public void setPublicationStatus(PublicationStatus publicationStatus) {
        this.publicationStatus = publicationStatus;
        touch();
    }

    public VisibilityStatus getVisibilityStatus() {
        return visibilityStatus;
    }

    public void setVisibilityStatus(VisibilityStatus visibilityStatus) {
        this.visibilityStatus = visibilityStatus;
        touch();
    }

    public boolean isDailyStatusActive() {
        return dailyStatusText != null
                && (dailyStatusExpiresAt == null || Instant.now().isBefore(dailyStatusExpiresAt));
    }

    /**
     * @return the status text, or null once the 24h window has passed
     */
    public String getDailyStatusText() {
        return isDailyStatusActive() ? dailyStatusText : null;
    }

    public Instant getDailyStatusExpiresAt() {
        return isDailyStatusActive() ? dailyStatusExpiresAt : null;
    }

    public void setDailyStatus(String text, Instant expiresAt) {
        this.dailyStatusText = text;
        this.dailyStatusExpiresAt = expiresAt;
        touch();
    }

    public Set<CommunicationTag> getTags() {
        return tags;
    }

    public void replaceTags(Set<CommunicationTag> newTags) {
        this.tags.clear();
        this.tags.addAll(newTags);
        touch();
    }

    public Integer getHeightCm() {
        return heightCm;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void setHeightCm(Integer heightCm) {
        this.heightCm = heightCm;
        touch();
    }

    public Set<RelationshipGoal> getGoals() {
        return goals;
    }

    public void replaceGoals(Set<RelationshipGoal> newGoals) {
        this.goals.clear();
        this.goals.addAll(newGoals);
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
