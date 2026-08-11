package com.smartdelivery.delivery.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The other half of the transactional outbox (ADR 004): polls {@code PENDING} rows
 * written by {@link DeliveryEventPublisher} and actually sends them to Kafka, out of
 * band from the request/consumer thread that wrote them. See order-service's
 * OutboxPublisher (identical implementation, Phase 8) for the full rationale.
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
