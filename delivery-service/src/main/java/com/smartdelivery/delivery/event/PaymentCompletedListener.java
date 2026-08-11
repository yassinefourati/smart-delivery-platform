package com.smartdelivery.delivery.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.delivery.service.ShipmentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to a paid order by creating its Shipment -- the "React to order confirmation
 * events to create a shipment" responsibility service-boundaries.md assigns to
 * delivery-service. A parse failure, or any other exception, throws and lets the
 * container's {@link org.springframework.kafka.listener.DefaultErrorHandler} (see
 * KafkaConsumerConfig) apply the platform's usual bounded retry + dead-letter policy.
 */
@Component
public class PaymentCompletedListener {

    private final ObjectMapper objectMapper;
    private final ShipmentService shipmentService;

    public PaymentCompletedListener(ObjectMapper objectMapper, ShipmentService shipmentService) {
        this.objectMapper = objectMapper;
        this.shipmentService = shipmentService;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED)
    public void onPaymentCompleted(String message) throws JsonProcessingException {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        PaymentCompletedPayload payload = objectMapper.treeToValue(envelope.payload(), PaymentCompletedPayload.class);
        shipmentService.createForOrder(payload.orderId());
    }
}
