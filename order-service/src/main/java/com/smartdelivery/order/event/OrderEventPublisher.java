package com.smartdelivery.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records order-lifecycle facts into the transactional outbox (ADR 004) rather than
 * sending to Kafka directly. Callers must invoke this from inside the same
 * {@code @Transactional} method that made the business change it announces (see
 * {@link com.smartdelivery.order.service.OrderService}) -- that's what makes "the order
 * was saved" and "the outbox row announcing it exists" atomic. The actual Kafka send
 * happens later, out of band, in {@link OutboxPublisher}.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
    private static final String SOURCE = "order-service";
    private static final String AGGREGATE_TYPE = "Order";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publishOrderCreated(Order order) {
        var items = order.getItems().stream()
                .map(item -> new OrderItemEventPayload(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity()))
                .toList();
        var payload = new OrderCreatedPayload(order.getId(), order.getUserId(), items, order.getTotalAmount());
        record(KafkaTopics.ORDER_CREATED, "OrderCreated", order.getId(), payload);
    }

    public void publishOrderCancelled(Order order) {
        var payload = new OrderCancelledPayload(order.getId(), order.getUserId(), "Cancelled by customer");
        record(KafkaTopics.ORDER_CANCELLED, "OrderCancelled", order.getId(), payload);
    }

    private void record(String topic, String eventType, UUID aggregateId, Object payload) {
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
            log.error("Failed to serialize {} for aggregate {}; outbox row was not written", eventType, aggregateId, e);
            return;
        }

        outboxEventRepository.save(new OutboxEvent(AGGREGATE_TYPE, aggregateId, eventType, topic, json));
    }
}
