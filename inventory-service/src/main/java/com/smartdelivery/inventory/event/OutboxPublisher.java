package com.smartdelivery.inventory.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The other half of the transactional outbox (ADR 004): polls {@code PENDING} rows
 * written by {@link InventoryEventPublisher} and actually sends them to Kafka, out of
 * band from the request thread that wrote them.
 *
 * Deliberately not {@code @Transactional} -- each poll is a sequence of independent,
 * single-row {@code save()} calls (already atomic on their own via Spring Data), not a
 * multi-statement unit that needs its own transaction boundary. A row that fails to
 * publish is left {@code PENDING} (with {@code attempts}/{@code lastError} updated for
 * observability) rather than moved to any kind of dead-letter state -- see the ADR: a
 * publish failure here is retried indefinitely on the next poll, since "publish an
 * already-published row again" is the only failure mode this needs to tolerate, and
 * every consumer already must.
 *
 * The synchronous {@code kafkaTemplate.send(...).get(...)} per row is a simplicity
 * trade-off appropriate to this phase: it serializes the batch on the scheduler thread
 * instead of pipelining sends, which is fine at this platform's scale and keeps
 * "did this row actually get published" trivially easy to reason about.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:2000}")
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING, PageRequest.of(0, BATCH_SIZE));
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
        } catch (Exception e) {
            event.recordFailedAttempt(e.getMessage());
            log.warn("Failed to publish outbox event {} ({}) to topic {}; will retry on next poll",
                    event.getId(), event.getEventType(), event.getTopic(), e);
        }
        outboxEventRepository.save(event);
    }
}
