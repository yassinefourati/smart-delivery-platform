package com.smartdelivery.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Deletes outbox rows that have done their job (ADR 006).
 *
 * Nothing removed published rows before Phase 15, so {@code outbox_events} grew forever:
 * every order, reservation, charge, and shipment this service ever announced stayed in
 * the table, slowing down the poller's own index scans and the backups alike. A
 * published row is still worth keeping for a while -- it is the audit trail for "was
 * this event really published, and when?" -- but not indefinitely, hence
 * {@code outbox.retention}.
 *
 * Only {@code PUBLISHED} rows are ever eligible. A {@code PENDING} row is never deleted
 * no matter how old it is: age there means the event is stuck, and deleting it would
 * turn a visible backlog into the silent event loss the outbox exists to prevent.
 */
@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository,
                            OutboxProperties properties,
                            OutboxMetrics metrics,
                            PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.metrics = metrics;
        // Same reasoning as OutboxPublisher's: an explicit template, not @Transactional
        // on a @Scheduled method whose proxying is order-dependent. Here it also gives
        // each batch its own transaction, which is the point of batching at all.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${outbox.cleanup-interval-ms:3600000}")
    public void deleteExpiredPublishedEvents() {
        Instant cutoff = Instant.now().minus(properties.retention());
        int deletedTotal = 0;

        for (int batch = 0; batch < properties.cleanupMaxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status ->
                    outboxEventRepository.deletePublishedBefore(cutoff, properties.cleanupBatchSize()));
            int deletedInBatch = deleted == null ? 0 : deleted;
            deletedTotal += deletedInBatch;
            // A short batch means the expired rows ran out -- or that a peer instance
            // claimed the rest, which is the same thing from here.
            if (deletedInBatch < properties.cleanupBatchSize()) {
                break;
            }
        }

        if (deletedTotal > 0) {
            metrics.recordCleanupDeleted(deletedTotal);
            log.info("Deleted {} published outbox events older than {}", deletedTotal, cutoff);
        }
    }
}
