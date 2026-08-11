package com.smartdelivery.order.event;

import java.util.UUID;

public record ShipmentCreatedPayload(UUID orderId, UUID shipmentId) {
}
