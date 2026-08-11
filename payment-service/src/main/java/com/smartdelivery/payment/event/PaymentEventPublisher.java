package com.smartdelivery.payment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records payment-lifecycle facts into the transactional outbox (ADR 004) rather than
 * sending to Kafka directly. Callers must invoke this from inside the same
 * {@code @Transactional} method that made the business change it announces (see
 * {@link com.smartdelivery.payment.service.PaymentService}) -- that's what makes "the
 * payment was saved" and "the outbox row announcing it exists" atomic. The actual Kafka
 * send happens later, out of band, in {@link OutboxPublisher}.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final String SOURCE = "payment-service";
    private static final String AGGREGATE_TYPE = "Payment";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publishCompleted(PaymentCompletedPayload payload) {
        record(KafkaTopics.PAYMENT_COMPLETED, "PaymentCompleted", payload.orderId(), payload);
    }

    public void publishFailed(PaymentFailedPayload payload) {
        record(KafkaTopics.PAYMENT_FAILED, "PaymentFailed", payload.orderId(), payload);
    }

    private void record(String topic, String eventType, UUID aggregateId, Object payload) {
        // A fresh correlationId per publish is a Phase 6 stand-in -- propagating the
        // correlationId that originated the request through to here is Phase 12's job
        // (docs/observability.md).
        var envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, 1, Instant.now(), UUID.randomUUID(), SOURCE,
                objectMapper.valueToTree(payload));

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize {} for aggregate {}; outbox row was not written", eventType, aggregateId, e);
            return;
        }

        outboxEventRepository.save(new OutboxEvent(AGGREGATE_TYPE, aggregateId, eventType, topic, json));
    }
}
