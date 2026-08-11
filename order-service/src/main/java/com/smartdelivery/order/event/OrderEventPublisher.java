package com.smartdelivery.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes order-lifecycle facts to Kafka. See InventoryEventPublisher's Javadoc for
 * the same caveat that applies here: this is a direct {@code KafkaTemplate.send()}
 * called after the owning transaction already committed, not yet the transactional
 * outbox ADR 004 describes -- that lands in Phase 8. A publish failure is logged, not
 * escalated to the caller; the order itself is already correctly persisted either way.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
    private static final String SOURCE = "order-service";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishOrderCreated(Order order) {
        var items = order.getItems().stream()
                .map(item -> new OrderItemEventPayload(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity()))
                .toList();
        var payload = new OrderCreatedPayload(order.getId(), order.getUserId(), items, order.getTotalAmount());
        publish(KafkaTopics.ORDER_CREATED, "OrderCreated", order.getId(), payload);
    }

    public void publishOrderCancelled(Order order) {
        var payload = new OrderCancelledPayload(order.getId(), order.getUserId(), "Cancelled by customer");
        publish(KafkaTopics.ORDER_CANCELLED, "OrderCancelled", order.getId(), payload);
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
