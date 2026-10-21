package com.dating.profiles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Group of interests («Спорт», «Кино и сериалы», …). Seeded by migration 029. */
@Entity
@Table(name = "interest_categories")
public class InterestCategory {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected InterestCategory() {
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
}
