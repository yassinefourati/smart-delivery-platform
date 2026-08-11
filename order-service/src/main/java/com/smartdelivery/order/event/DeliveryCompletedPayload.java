package com.smartdelivery.order.event;

import java.time.Instant;
import java.util.UUID;

public record DeliveryCompletedPayload(UUID orderId, UUID shipmentId, Instant deliveredAt) {
}
