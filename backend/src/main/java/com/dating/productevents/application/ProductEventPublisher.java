package com.dating.productevents.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Records product analytics events (no PII: no phone, names, texts or account ids).
 * <p>
 * Events are written only after the business transaction commits, in a separate transaction,
 * and any failure is swallowed: analytics must never break or roll back the main operation.
 */
@Service
public class ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductEventPublisher.class);

    private final ProductEventWriter writer;

    public ProductEventPublisher(ProductEventWriter writer) {
        this.writer = writer;
    }

    public void publish(String eventType, Map<String, ?> attributes) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeSafely(eventType, attributes);
                }
            });
        } else {
            writeSafely(eventType, attributes);
        }
    }

    private void writeSafely(String eventType, Map<String, ?> attributes) {
        try {
            writer.write(eventType, attributes);
        } catch (RuntimeException e) {
            log.warn("Product event '{}' was not recorded: {}", eventType, e.getMessage());
        }
    }
}
