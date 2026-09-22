package com.smartdelivery.inventory.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The other half of the transactional outbox (ADR 004): claims {@code PENDING} rows
 * written by {@link InventoryEventPublisher} and actually sends them to Kafka, out of
 * band from the request/consumer thread that wrote them.
 *
 * Phase 15 (ADR 006) replaced the Phase 8 "read the oldest PENDING rows, send them all"
 * loop, which was only correct with exactly one instance running. The claim now happens
 * inside a transaction via {@link OutboxEventRepository#claimNextBatch} -- see that
 * method for how {@code FOR UPDATE SKIP LOCKED} and the oldest-per-aggregate
 * restriction together give both exactly-one-publisher-per-row and per-aggregate
 * ordering across any number of instances.
 *
 * The transaction deliberately spans the sends as well as the claim, rather than
 * claiming, committing, and sending afterward. That keeps the row locks held for the
 * whole batch, which is what makes "claimed by this instance" mean anything at all
 * without a second claimed/in-flight status and a reaper for rows orphaned by a crash:
 * an instance that dies mid-batch simply rolls back, and its rows are plain
 * {@code PENDING} again for whoever polls next. The cost is a database transaction held
 * open across network I/O, bounded by {@code outbox.batch-size} x
 * {@link #SEND_TIMEOUT_SECONDS}; tune the batch size down if that bound is too loose
 * for the deployment.
 *
 * The synchronous {@code kafkaTemplate.send(...).get(...)} per row is the same
 * simplicity trade-off as in Phase 8: it serializes the batch on the scheduler thread
 * instead of pipelining sends, which is fine at this platform's scale -- and, now that
 * ordering is a guarantee rather than an accident, is also what makes "this row was
 * published before that one" true rather than merely likely.
 *
 * A row that fails to send is still left {@code PENDING} and still retried forever (see
 * {@link OutboxEvent#recordFailedAttempt}); the only change is that it now backs off
 * between attempts instead of being retried every poll.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties,
                           OutboxMetrics metrics,
                           PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.metrics = metrics;
        // An explicit TransactionTemplate rather than @Transactional on this method:
        // @Scheduled and AOP proxying are both bean post-processors, so whether the
        // scheduler ends up invoking the proxy (and the annotation therefore applies at
        // all) depends on their relative ordering. A claim whose locking clause silently
        // does nothing because it ran outside a transaction is precisely the bug this
        // phase exists to fix, so it is not left to that.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:2000}")
    public void publishPending() {
        transactionTemplate.executeWithoutResult(status -> publishBatch());
    }

    private void publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.claimNextBatch(Instant.now(), properties.batchSize());
        // The claim query already returns at most one row per aggregate, so in practice
        // nothing is ever added to this set. It is kept, and tested, because the loop's
        // correctness depends on the rule rather than on that query's current shape: if
        // claiming several rows per aggregate is ever worth the throughput, a failure
        // must still stop that aggregate's stream there and then, not skip past it.
        Set<UUID> blockedAggregates = new HashSet<>();
        for (OutboxEvent event : batch) {
            if (blockedAggregates.contains(event.getAggregateId())) {
                log.debug("Holding outbox event {} ({}): an earlier event for aggregate {} failed to publish",
                        event.getId(), event.getEventType(), event.getAggregateId());
                continue;
            }
            if (!publishOne(event)) {
                blockedAggregates.add(event.getAggregateId());
            }
        }
    }

    /** @return whether the send succeeded; {@code false} blocks the rest of this aggregate's batch. */
    private boolean publishOne(OutboxEvent event) {
        boolean published;
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
            metrics.recordPublished(event.getEventType());
            published = true;
        } catch (Exception e) {
            event.recordFailedAttempt(e.getMessage(), properties.initialBackoff(), properties.maxBackoff());
            metrics.recordPublishFailure(event.getEventType());
            log.warn("Failed to publish outbox event {} ({}) to topic {}; attempt {}, next attempt no earlier than {}",
                    event.getId(), event.getEventType(), event.getTopic(), event.getAttempts(),
                    event.getNextAttemptAt(), e);
            published = false;
        }
        outboxEventRepository.save(event);
        return published;
    }
}
