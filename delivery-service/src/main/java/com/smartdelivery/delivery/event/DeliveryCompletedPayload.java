package com.smartdelivery.delivery.event;

import java.time.Instant;
import java.util.UUID;

/** Must match order-service's local copy field-for-field -- see docs/kafka-events.md. */
public record DeliveryCompletedPayload(UUID orderId, UUID shipmentId, Instant deliveredAt) {
}
