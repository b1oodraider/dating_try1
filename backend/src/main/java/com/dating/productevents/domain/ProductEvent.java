package com.dating.productevents.domain;

import com.dating.common.persistence.AssignedIdEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_events")
public class ProductEvent extends AssignedIdEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "attributes_json", length = 2000)
    private String attributesJson;

    protected ProductEvent() {
    }

    public ProductEvent(UUID eventId, String eventType, int schemaVersion, String attributesJson) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.occurredAt = Instant.now();
        this.attributesJson = attributesJson;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getAttributesJson() {
        return attributesJson;
    }
}
