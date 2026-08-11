package com.smartdelivery.notification.event;

import java.time.Instant;
import java.util.UUID;

public record DeliveryCompletedPayload(UUID orderId, UUID shipmentId, Instant deliveredAt) {
}
