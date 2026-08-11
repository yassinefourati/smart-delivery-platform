package com.smartdelivery.notification.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.notification.notify.NotificationSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationSender sender;

    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private NotificationEventListener listener() {
        return new NotificationEventListener(objectMapper, sender);
    }

    private String envelopeJson(String eventType, Object payload) throws Exception {
        var envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, 1, Instant.now(), UUID.randomUUID(), "test",
                objectMapper.valueToTree(payload));
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    void onOrderCreatedRendersOrderAndUserAndAmount() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var payload = new OrderCreatedPayload(orderId, userId,
                List.of(new OrderItemEventPayload(UUID.randomUUID(), "Widget", new BigDecimal("9.99"), 2)),
                new BigDecimal("19.98"));

        listener().onOrderCreated(envelopeJson("OrderCreated", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), userId.toString(), "19.98");
    }

    @Test
    void onOrderCancelledRendersOrderAndReason() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new OrderCancelledPayload(orderId, UUID.randomUUID(), "Cancelled by customer");

        listener().onOrderCancelled(envelopeJson("OrderCancelled", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), "Cancelled by customer");
    }

    @Test
    void onInventoryReservedRendersOrderAndQuantity() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new InventoryReservedPayload(UUID.randomUUID(), orderId, UUID.randomUUID(), 3);

        listener().onInventoryReserved(envelopeJson("InventoryReserved", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), "3");
    }

    @Test
    void onInventoryReleasedRendersOrderAndQuantity() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new InventoryReleasedPayload(UUID.randomUUID(), orderId, UUID.randomUUID(), 1);

        listener().onInventoryReleased(envelopeJson("InventoryReleased", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString());
    }

    @Test
    void onInventoryFailedRendersOrderAndReason() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new InventoryFailedPayload(orderId, UUID.randomUUID(), 5, "INSUFFICIENT_STOCK");

        listener().onInventoryFailed(envelopeJson("InventoryFailed", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), "INSUFFICIENT_STOCK");
    }

    @Test
    void onPaymentCompletedRendersOrderAndAmount() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("50.00"));

        listener().onPaymentCompleted(envelopeJson("PaymentCompleted", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), "50.00");
    }

    @Test
    void onPaymentFailedRendersOrderAndReason() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new PaymentFailedPayload(orderId, "Payment declined");

        listener().onPaymentFailed(envelopeJson("PaymentFailed", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), "Payment declined");
    }

    @Test
    void onShipmentCreatedRendersOrderAndShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        var payload = new ShipmentCreatedPayload(orderId, shipmentId);

        listener().onShipmentCreated(envelopeJson("ShipmentCreated", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), shipmentId.toString());
    }

    @Test
    void onDeliveryAssignedRendersOrderShipmentAndAgent() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        var payload = new DeliveryAssignedPayload(orderId, shipmentId, agentId);

        listener().onDeliveryAssigned(envelopeJson("DeliveryAssigned", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), shipmentId.toString(), agentId.toString());
    }

    @Test
    void onDeliveryCompletedRendersOrderAndDeliveredAt() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant deliveredAt = Instant.now();
        var payload = new DeliveryCompletedPayload(orderId, shipmentId, deliveredAt);

        listener().onDeliveryCompleted(envelopeJson("DeliveryCompleted", payload));

        verify(sender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains(orderId.toString(), shipmentId.toString());
    }
}
