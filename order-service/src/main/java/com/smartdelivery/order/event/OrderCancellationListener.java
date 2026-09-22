package com.smartdelivery.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.service.OrderSagaOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-service consuming its own {@code order.cancelled} event to compensate for the
 * cancellation, mirroring how {@link OrderSagaStartListener} starts the saga off
 * {@code order.created} (ADR 008).
 *
 * Before Phase 17 this ran inline in the cancel request, after {@code OrderService.cancel}
 * had already committed. That left a window with no owner: if the refund or release call
 * failed, or the pod died between the commit and the call, the order was CANCELLED with
 * its stock still held and its payment still taken, and nothing anywhere would ever try
 * again. The event is written in the same transaction as the cancellation, so "the order
 * was cancelled" and "something will compensate for it" are now one atomic fact -- and
 * compensation inherits the bounded retry and dead-letter handling every other consumer
 * here already has (KafkaConsumerConfig), rather than needing retry logic of its own.
 *
 * Compensation is idempotent at every step (inventory release is keyed by order+product,
 * refund by order), so a redelivered event -- Kafka is at-least-once -- releases nothing
 * twice and refunds nothing twice.
 */
@Component
public class OrderCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationListener.class);

    private final ObjectMapper objectMapper;
    private final OrderSagaOrchestrator orchestrator;

    public OrderCancellationListener(ObjectMapper objectMapper, OrderSagaOrchestrator orchestrator) {
        this.objectMapper = objectMapper;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(topics = KafkaTopics.ORDER_CANCELLED, groupId = "order-service-cancellation")
    public void onOrderCancelled(String message) throws JsonProcessingException {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        OrderCancelledPayload payload = objectMapper.treeToValue(envelope.payload(), OrderCancelledPayload.class);

        OrderStatus previousStatus = parsePreviousStatus(payload);
        if (previousStatus == null) {
            return;
        }

        MDC.put("correlationId", envelope.correlationId().toString());
        try {
            orchestrator.compensateCancellation(payload.orderId(), previousStatus);
        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * An event published before Phase 17 has no {@code previousStatus}, and there is no
     * way to reconstruct it -- the order has read back as CANCELLED ever since. Skipping
     * is the honest answer and also the correct one: such an event was already
     * compensated synchronously at the time it was published, by the code this listener
     * replaced. An unparseable value is a different matter and is left to throw, so it
     * reaches the dead-letter topic rather than being silently dropped.
     */
    private OrderStatus parsePreviousStatus(OrderCancelledPayload payload) {
        if (payload.previousStatus() == null || payload.previousStatus().isBlank()) {
            log.warn("order.cancelled for order {} carries no previousStatus (pre-Phase-17 event); "
                    + "skipping compensation, which ran inline when it was published", payload.orderId());
            return null;
        }
        return OrderStatus.valueOf(payload.previousStatus());
    }
}
