package com.dating.profiles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Reference data: what a person is looking for (serious relationship, friendship, …). Rows are seeded by migration.
 */
@Entity
@Table(name = "relationship_goals")
public class RelationshipGoal {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    protected RelationshipGoal() {
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
