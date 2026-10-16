package com.dating.profiles.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "communication_tags")
public class CommunicationTag {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(name = "category_label", nullable = false, length = 50)
    private String categoryLabel;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Place on its category's scale (see tag_categories.scale); null = not placed. Written by the admin API. */
    @Column(name = "scale_position", insertable = false, updatable = false)
    private Integer scalePosition;

    public Integer getScalePosition() {
        return scalePosition;
    }

    protected CommunicationTag() {
    }

    public CommunicationTag(UUID id, String code, String category, String categoryLabel, String label, String description, int sortOrder) {
        this.id = id;
        this.code = code;
        this.category = category;
        this.categoryLabel = categoryLabel;
        this.label = label;
        this.description = description;
        this.active = true;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getCategory() {
        return category;
    }

    public String getCategoryLabel() {
        return categoryLabel;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
