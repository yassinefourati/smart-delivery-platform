package com.smartdelivery.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.notification.notify.NotificationSender;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One {@code @KafkaListener} per topic in the platform's catalog (see
 * docs/kafka-events.md) -- this is the one consumer of every event type, since
 * notification-service's whole job is turning "something happened" into a
 * human-readable line via {@link NotificationSender}. A parse failure, or any other
 * exception, throws and lets the container's
 * {@link org.springframework.kafka.listener.DefaultErrorHandler} (see
 * KafkaConsumerConfig) apply the platform's usual bounded retry + dead-letter policy,
 * same as every other consumer here.
 *
 * Sets each consumed event's {@code correlationId} into MDC for the duration of
 * handling it (see CorrelationIdFilter's Javadoc in the other services,
 * docs/observability.md) -- purely for the log line {@link NotificationSender} writes;
 * this service never makes a further call or write that would need to carry it onward.
 *
 * Monetary fields ({@code totalAmount}, {@code amount}) are rendered with {@code %.2f},
 * not a bare {@code %s} on the {@code BigDecimal} itself: round-tripping a BigDecimal
 * through the envelope's untyped {@code JsonNode} payload (POJO -> tree -> JSON text ->
 * tree -> POJO, the same path every producer/consumer here uses) does not reliably
 * preserve its original scale -- Jackson may normalize {@code 50.00} down to a
 * differently-scaled equivalent (still numerically 50, just not printing as
 * {@code "50.00"} anymore). The numeric *value* is never wrong, only its default
 * {@code toString()}; {@code %.2f} formats off the actual value and is unaffected
 * either way, which is what a customer-facing amount needs.
 */
@Component
public class NotificationEventListener {

    private final ObjectMapper objectMapper;
    private final NotificationSender sender;

    public NotificationEventListener(ObjectMapper objectMapper, NotificationSender sender) {
        this.objectMapper = objectMapper;
        this.sender = sender;
    }

    @KafkaListener(topics = KafkaTopics.ORDER_CREATED)
    public void onOrderCreated(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, OrderCreatedPayload.class);
        withCorrelation(envelope, () -> sender.send(
                "Order %s placed by user %s for %.2f".formatted(payload.orderId(), payload.userId(), payload.totalAmount())));
    }

    @KafkaListener(topics = KafkaTopics.ORDER_CANCELLED)
    public void onOrderCancelled(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, OrderCancelledPayload.class);
        withCorrelation(envelope, () -> sender.send(
                "Order %s cancelled for user %s (%s)".formatted(payload.orderId(), payload.userId(), payload.reason())));
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_RESERVED)
    public void onInventoryReserved(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, InventoryReservedPayload.class);
        withCorrelation(envelope, () -> sender.send("Inventory reserved for order %s: %d unit(s) of product %s"
                .formatted(payload.orderId(), payload.quantity(), payload.productId())));
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_RELEASED)
    public void onInventoryReleased(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, InventoryReleasedPayload.class);
        withCorrelation(envelope, () -> sender.send("Inventory released for order %s: %d unit(s) of product %s"
                .formatted(payload.orderId(), payload.quantity(), payload.productId())));
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED)
    public void onInventoryFailed(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, InventoryFailedPayload.class);
        withCorrelation(envelope, () -> sender.send(
                "Inventory reservation failed for order %s: %s".formatted(payload.orderId(), payload.reason())));
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED)
    public void onPaymentCompleted(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, PaymentCompletedPayload.class);
        withCorrelation(envelope, () -> sender.send(
                "Payment %s of %.2f completed for order %s".formatted(payload.paymentId(), payload.amount(), payload.orderId())));
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED)
    public void onPaymentFailed(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, PaymentFailedPayload.class);
        withCorrelation(envelope, () -> sender.send(
                "Payment failed for order %s: %s".formatted(payload.orderId(), payload.reason())));
    }

    @KafkaListener(topics = KafkaTopics.SHIPMENT_CREATED)
    public void onShipmentCreated(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, ShipmentCreatedPayload.class);
        withCorrelation(envelope, () -> sender.send(
                "Shipment %s created for order %s".formatted(payload.shipmentId(), payload.orderId())));
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_ASSIGNED)
    public void onDeliveryAssigned(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, DeliveryAssignedPayload.class);
        withCorrelation(envelope, () -> sender.send("Delivery agent %s assigned to shipment %s (order %s)"
                .formatted(payload.agentId(), payload.shipmentId(), payload.orderId())));
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_COMPLETED)
    public void onDeliveryCompleted(String message) throws JsonProcessingException {
        EventEnvelope envelope = parseEnvelope(message);
        var payload = parsePayload(envelope, DeliveryCompletedPayload.class);
        withCorrelation(envelope, () -> sender.send("Order %s delivered at %s (shipment %s)"
                .formatted(payload.orderId(), payload.deliveredAt(), payload.shipmentId())));
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
