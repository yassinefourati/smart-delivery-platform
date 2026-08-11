package com.smartdelivery.payment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes payment-lifecycle facts to Kafka after the owning transaction has already
 * committed. Same known gap as order-service's OrderEventPublisher and
 * inventory-service's InventoryEventPublisher: a direct {@code KafkaTemplate.send()},
 * not yet the outbox pattern (ADR 004, Phase 8). A publish failure is logged, not
 * escalated -- the Payment record itself is already correctly persisted either way.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final String SOURCE = "payment-service";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishCompleted(PaymentCompletedPayload payload) {
        publish(KafkaTopics.PAYMENT_COMPLETED, "PaymentCompleted", payload.orderId(), payload);
    }

    public void publishFailed(PaymentFailedPayload payload) {
        publish(KafkaTopics.PAYMENT_FAILED, "PaymentFailed", payload.orderId(), payload);
    }

    private void publish(String topic, String eventType, UUID key, Object payload) {
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
            log.error("Failed to serialize {} for key {}; event was not published", eventType, key, e);
            return;
        }

        kafkaTemplate.send(topic, key.toString(), json).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} to topic {} for key {}", eventType, topic, key, ex);
            }
        });
    }
}
