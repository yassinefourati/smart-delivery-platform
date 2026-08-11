package com.smartdelivery.inventory.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes inventory-lifecycle facts to Kafka, called after the owning database
 * transaction has already committed (see InventoryReservationOperations).
 *
 * KNOWN GAP, flagged rather than hidden: this is a direct {@code KafkaTemplate.send()},
 * not the transactional outbox pattern ADR 004 describes. If the process crashes
 * between the DB commit and this call actually reaching the broker, the reservation
 * itself is still correct (it already committed) but no event announces it -- other
 * services relying on this event (a future analytics-service, for instance) would
 * silently miss it. Phase 8 replaces this with an outbox write in the same
 * transaction as the reservation change, closing that gap. Until then, a publish
 * failure is logged, not retried or escalated to the caller -- the REST response
 * already reflects a successful, durable reservation regardless of whether the event
 * made it out.
 */
@Component
public class InventoryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventPublisher.class);
    private static final String SOURCE = "inventory-service";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public InventoryEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishReserved(InventoryReservedPayload payload) {
        publish(KafkaTopics.INVENTORY_RESERVED, "InventoryReserved", payload.orderId(), payload);
    }

    public void publishReleased(InventoryReleasedPayload payload) {
        publish(KafkaTopics.INVENTORY_RELEASED, "InventoryReleased", payload.orderId(), payload);
    }

    public void publishFailed(InventoryFailedPayload payload) {
        publish(KafkaTopics.INVENTORY_FAILED, "InventoryFailed", payload.orderId(), payload);
    }

    private void publish(String topic, String eventType, UUID key, Object payload) {
        // A fresh correlationId per publish is a Phase 6 stand-in -- propagating the
        // correlationId that originated the HTTP request through to here is Phase 12's
        // job (docs/observability.md).
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
