package com.dating.discovery.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Private, per-account settings of the discovery feed. Never exposed to other users.
 * Natural home for future (e.g. premium) feed filters.
 */
@Entity
@Table(name = "discovery_preferences")
public class DiscoveryPreferences extends AssignedIdEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "discovery_exclusion_words", joinColumns = @JoinColumn(name = "account_id"))
    @OrderColumn(name = "position")
    @Column(name = "word", nullable = false, length = 30)
    private List<String> exclusionWords = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DiscoveryPreferences() {
    }

    public DiscoveryPreferences(UUID accountId) {
        this.accountId = accountId;
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return accountId;
    }

    public List<String> getExclusionWords() {
        return List.copyOf(exclusionWords);
    }

    public void replaceExclusionWords(List<String> words) {
        exclusionWords.clear();
        exclusionWords.addAll(words);
        updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
