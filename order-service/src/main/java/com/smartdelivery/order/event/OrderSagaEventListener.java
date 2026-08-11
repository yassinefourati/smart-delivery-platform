package com.smartdelivery.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.service.OrderSagaEventHandler;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Deserializes each topic's envelope + payload and delegates to
 * {@link OrderSagaEventHandler}. A parse failure throws, which the container's
 * {@link org.springframework.kafka.listener.DefaultErrorHandler} (see
 * KafkaConsumerConfig) treats the same as a handler failure: bounded retry, then
 * dead-letter -- a malformed message is exactly the kind of "poison message" that
 * policy exists for.
 *
 * Sets each consumed event's {@code correlationId} into MDC for the duration of
 * handling it (see CorrelationIdFilter, docs/observability.md), so a transition this
 * class applies -- and any outbox row that transition causes to be written -- carries
 * the same id as the request that originally caused the event, all the way back to
 * whichever HTTP call started this order's saga.
 */
@Component
public class OrderSagaEventListener {

    private final ObjectMapper objectMapper;
    private final OrderSagaEventHandler handler;

    public OrderSagaEventListener(ObjectMapper objectMapper, OrderSagaEventHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_RESERVED)
    public void onInventoryReserved(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, InventoryReservedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handleInventoryReserved(orderId));
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED)
    public void onInventoryFailed(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, InventoryFailedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handleInventoryFailed(orderId));
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED)
    public void onPaymentCompleted(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, PaymentCompletedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handlePaymentCompleted(orderId));
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED)
    public void onPaymentFailed(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, PaymentFailedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handlePaymentFailed(orderId));
    }

    @KafkaListener(topics = KafkaTopics.SHIPMENT_CREATED)
    public void onShipmentCreated(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, ShipmentCreatedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handleShipmentCreated(orderId));
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_ASSIGNED)
    public void onDeliveryAssigned(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, DeliveryAssignedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handleDeliveryAssigned(orderId));
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_COMPLETED)
    public void onDeliveryCompleted(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var orderId = parsePayload(envelope, DeliveryCompletedPayload.class).orderId();
        withCorrelation(envelope, () -> handler.handleDeliveryCompleted(orderId));
    }

    private EventEnvelope parseEnvelope(String message) throws JsonProcessingException {
        return objectMapper.readValue(message, EventEnvelope.class);
    }

    private <T> T parsePayload(EventEnvelope envelope, Class<T> payloadType) throws JsonProcessingException {
        return objectMapper.treeToValue(envelope.payload(), payloadType);
    }

    private void withCorrelation(EventEnvelope envelope, Runnable action) {
        MDC.put("correlationId", envelope.correlationId().toString());
        try {
            action.run();
        } finally {
            MDC.remove("correlationId");
        }
    }
}
