package com.dating.productevents.application;

import com.dating.productevents.domain.ProductEvent;
import com.dating.productevents.persistence.ProductEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductEventPublisher {

    private final ProductEventRepository eventRepository;

    public ProductEventPublisher(ProductEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * Publishes a minimal product event without blocking or failing the main transaction if event logging fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishEvent(String eventType, String attributesJson) {
        try {
            ProductEvent event = new ProductEvent(UUID.randomUUID(), eventType, 1, attributesJson);
            eventRepository.save(event);
        } catch (Exception ignored) {
            // As per requirements: "Побочные события не должны ломать основную операцию, если аналитика недоступна"
        }
    }
}
