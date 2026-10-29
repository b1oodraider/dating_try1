package com.dating.communities.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.UUID;

/**
 * A subscription to an interest group, mapped only so the feed can filter by groups in its JPA query
 * (subscriptions are written with SQL by CommunityService).
 */
@Entity
@Table(name = "community_members")
public class CommunityMember {

    @Embeddable
    public record Key(@Column(name = "account_id") UUID accountId, @Column(name = "community_id") Long communityId)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    protected CommunityMember() {
    }

    public Key getId() {
        return id;
    }
}
