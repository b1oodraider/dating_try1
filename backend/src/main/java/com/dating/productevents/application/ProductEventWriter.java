package com.dating.productevents.application;

import com.dating.productevents.domain.ProductEvent;
import com.dating.productevents.persistence.ProductEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

@Component
public class ProductEventWriter {

    static final int SCHEMA_VERSION = 1;

    private final ProductEventRepository eventRepository;
    private final JsonMapper jsonMapper;

    public ProductEventWriter(ProductEventRepository eventRepository, JsonMapper jsonMapper) {
        this.eventRepository = eventRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String eventType, Map<String, ?> attributes) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(attributes == null ? Map.of() : attributes);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Event attributes are not serializable", e);
        }
        eventRepository.save(new ProductEvent(UUID.randomUUID(), eventType, SCHEMA_VERSION, json));
    }
}
