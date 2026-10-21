package com.dating.profiles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An interest from the closed dictionary (docs/matching.md, part 1); up to 10 per profile. */
@Entity
@Table(name = "interests")
public class Interest {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 60)
    private String label;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_code")
    private InterestCategory category;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    protected Interest() {
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public InterestCategory getCategory() {
        return category;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
