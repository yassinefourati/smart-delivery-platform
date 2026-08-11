package com.smartdelivery.order.event;

import java.util.UUID;

public record OrderCancelledPayload(UUID orderId, UUID userId, String reason) {
}
