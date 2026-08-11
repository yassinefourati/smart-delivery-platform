package com.smartdelivery.delivery.event;

import java.util.UUID;

/** Must match order-service's local copy field-for-field -- see docs/kafka-events.md. */
public record ShipmentCreatedPayload(UUID orderId, UUID shipmentId) {
}
