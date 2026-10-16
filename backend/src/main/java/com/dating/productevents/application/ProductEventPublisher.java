package com.dating.productevents.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Records product analytics events (no PII: no phone, names, texts or account ids).
 * <p>
 * Events are written only after the business transaction commits, in a separate transaction on another thread,
 * and any failure is swallowed: analytics must never break or roll back the main operation.
 * <p>
 * The other thread matters: afterCommit runs while the committing thread still holds its connection, so writing
 * there needs a second one; under load every request holds one and waits for another, and the pool deadlocks.
 */
@Service
public class ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductEventPublisher.class);

    private final ProductEventWriter writer;
    private final TaskExecutor executor;

    public ProductEventPublisher(ProductEventWriter writer,
                                 @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.writer = writer;
        this.executor = executor;
    }

    public void publish(String eventType, Map<String, ?> attributes) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeLater(eventType, attributes);
                }
            });
        } else {
            writeSafely(eventType, attributes);
        }
    }

    private void writeLater(String eventType, Map<String, ?> attributes) {
        try {
            executor.execute(() -> writeSafely(eventType, attributes));
        } catch (RuntimeException e) {
            log.warn("Product event '{}' was not recorded: {}", eventType, e.getMessage());
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
