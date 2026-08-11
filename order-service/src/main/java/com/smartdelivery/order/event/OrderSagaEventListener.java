package com.smartdelivery.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.service.OrderSagaEventHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Deserializes each topic's envelope + payload and delegates to
 * {@link OrderSagaEventHandler}. A parse failure throws, which the container's
 * {@link org.springframework.kafka.listener.DefaultErrorHandler} (see
 * KafkaConsumerConfig) treats the same as a handler failure: bounded retry, then
 * dead-letter -- a malformed message is exactly the kind of "poison message" that
 * policy exists for.
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
        handler.handleInventoryReserved(parse(message, InventoryReservedPayload.class).orderId());
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED)
    public void onInventoryFailed(String message) throws JsonProcessingException {
        handler.handleInventoryFailed(parse(message, InventoryFailedPayload.class).orderId());
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED)
    public void onPaymentCompleted(String message) throws JsonProcessingException {
        handler.handlePaymentCompleted(parse(message, PaymentCompletedPayload.class).orderId());
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED)
    public void onPaymentFailed(String message) throws JsonProcessingException {
        handler.handlePaymentFailed(parse(message, PaymentFailedPayload.class).orderId());
    }

    @KafkaListener(topics = KafkaTopics.SHIPMENT_CREATED)
    public void onShipmentCreated(String message) throws JsonProcessingException {
        handler.handleShipmentCreated(parse(message, ShipmentCreatedPayload.class).orderId());
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_ASSIGNED)
    public void onDeliveryAssigned(String message) throws JsonProcessingException {
        handler.handleDeliveryAssigned(parse(message, DeliveryAssignedPayload.class).orderId());
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_COMPLETED)
    public void onDeliveryCompleted(String message) throws JsonProcessingException {
        handler.handleDeliveryCompleted(parse(message, DeliveryCompletedPayload.class).orderId());
    }

    private <T> T parse(String message, Class<T> payloadType) throws JsonProcessingException {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        return objectMapper.treeToValue(envelope.payload(), payloadType);
    }
}
