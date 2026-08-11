package com.smartdelivery.delivery.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records shipment/delivery-lifecycle facts into the transactional outbox (ADR 004)
 * rather than sending to Kafka directly. Callers must invoke this from inside the same
 * {@code @Transactional} method that made the business change it announces (see
 * {@link com.smartdelivery.delivery.service.ShipmentService} and
 * {@link com.smartdelivery.delivery.service.DeliveryService}) -- that's what makes "the
 * shipment/delivery was saved" and "the outbox row announcing it exists" atomic. The
 * actual Kafka send happens later, out of band, in {@link OutboxPublisher}.
 *
 * Events are keyed by {@code orderId}, not this service's own shipment/delivery id --
 * consistent with every other producer in this platform, since Kafka partitioning (and
 * therefore per-order ordering) is by {@code orderId} across the whole system, and
 * order-service (the primary consumer) only cares about its own order. See
 * docs/kafka-events.md.
 */
@Component
public class DeliveryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventPublisher.class);
    private static final String SOURCE = "delivery-service";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public DeliveryEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publishShipmentCreated(ShipmentCreatedPayload payload) {
        record("Shipment", KafkaTopics.SHIPMENT_CREATED, "ShipmentCreated", payload.orderId(), payload);
    }

    public void publishDeliveryAssigned(DeliveryAssignedPayload payload) {
        record("Delivery", KafkaTopics.DELIVERY_ASSIGNED, "DeliveryAssigned", payload.orderId(), payload);
    }

    public void publishDeliveryCompleted(DeliveryCompletedPayload payload) {
        record("Delivery", KafkaTopics.DELIVERY_COMPLETED, "DeliveryCompleted", payload.orderId(), payload);
    }

    private void record(String aggregateType, String topic, String eventType, UUID aggregateId, Object payload) {
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

        outboxEventRepository.save(new OutboxEvent(aggregateType, aggregateId, eventType, topic, json));
    }
}
