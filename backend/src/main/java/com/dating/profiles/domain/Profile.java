package com.dating.profiles.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
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

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 6)
    private Gender gender;

    /** The gender this person looks for. */
    @Enumerated(EnumType.STRING)
    @Column(name = "seeking", length = 6)
    private Gender seeking;

    /** Ages this person accepts; a candidate outside them never sees this profile either. */
    @Column(name = "pref_age_min", nullable = false)
    private int prefAgeMin = 18;

    /** null = no upper limit. */
    @Column(name = "pref_age_max")
    private Integer prefAgeMax;

    /** Display name of the city; city_id is what filters and distances use. */
    @Column(name = "city", length = 60)
    private String city;

    @Column(name = "city_id")
    private Integer cityId;

    /** Approximate location (rounded to ~1 km) when the person shares it; else the city centre is used. */
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "location_updated_at")
    private Instant locationUpdatedAt;

    /** Search radius in km; null = no radius (city filter or the whole country). */
    @Column(name = "search_radius_km")
    private Integer searchRadiusKm;

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

    /** Full years today; null until the birth date is known. */
    public Integer getAge() {
        return birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
        touch();
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
        touch();
    }

    public Gender getSeeking() {
        return seeking;
    }

    public void setSeeking(Gender seeking) {
        this.seeking = seeking;
        touch();
    }

    public int getPrefAgeMin() {
        return prefAgeMin;
    }

    public Integer getPrefAgeMax() {
        return prefAgeMax;
    }

    public void setPrefAgeRange(int min, Integer max) {
        this.prefAgeMin = min;
        this.prefAgeMax = max;
        touch();
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
        touch();
    }

    public Integer getCityId() {
        return cityId;
    }

    /** A city from the dictionary: its id and official name. */
    public void setCity(int cityId, String name) {
        this.cityId = cityId;
        this.city = name;
        touch();
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public boolean hasLocation() {
        return latitude != null;
    }

    /** Both null clears the shared location. Location updates do not count as profile edits. */
    public void setLocation(Double latitude, Double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationUpdatedAt = latitude == null ? null : Instant.now();
    }

    public Integer getSearchRadiusKm() {
        return searchRadiusKm;
    }

    public void setSearchRadiusKm(Integer searchRadiusKm) {
        this.searchRadiusKm = searchRadiusKm;
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
