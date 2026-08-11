package com.smartdelivery.notification.event;

import java.util.UUID;

public record ShipmentCreatedPayload(UUID orderId, UUID shipmentId) {
}
