package com.smartdelivery.inventory.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.platform.outbox.OutboxEvent;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records inventory-lifecycle facts into the transactional outbox (ADR 004) rather than
 * sending to Kafka directly. Callers must invoke this from inside the same
 * {@code @Transactional} method that made the business change it announces (see
 * {@link com.smartdelivery.inventory.service.InventoryReservationOperations}) -- that's
 * what makes "the reservation was saved" and "the outbox row announcing it exists"
 * atomic. The actual Kafka send happens later, out of band, in {@link OutboxPublisher}.
 */
@Component
public class InventoryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventPublisher.class);
    private static final String SOURCE = "inventory-service";
    private static final String AGGREGATE_TYPE = "InventoryReservation";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public InventoryEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publishReserved(InventoryReservedPayload payload) {
        record(KafkaTopics.INVENTORY_RESERVED, "InventoryReserved", payload.orderId(), payload);
    }

    public void publishReleased(InventoryReleasedPayload payload) {
        record(KafkaTopics.INVENTORY_RELEASED, "InventoryReleased", payload.orderId(), payload);
    }

    public void publishFailed(InventoryFailedPayload payload) {
        record(KafkaTopics.INVENTORY_FAILED, "InventoryFailed", payload.orderId(), payload);
    }

    private void record(String topic, String eventType, UUID aggregateId, Object payload) {
        var envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, 1, Instant.now(), currentCorrelationId(), SOURCE,
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

    /**
     * The id of the request (or, for a Kafka-triggered write, the event) that caused
     * this publish -- see CorrelationIdFilter. Falls back to a fresh id only if nothing
     * populated the MDC, which would itself be a bug elsewhere (every entry point into
     * this service sets it); this is a safety net, not the expected path.
     */
    private UUID currentCorrelationId() {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            try {
                return UUID.fromString(correlationId);
            } catch (IllegalArgumentException ignored) {
                // fall through to a fresh id below
            }
        }
        return UUID.randomUUID();
    }
}
