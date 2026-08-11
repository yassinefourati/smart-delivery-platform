package com.smartdelivery.notification.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreatedPayload(UUID orderId, UUID userId, List<OrderItemEventPayload> items, BigDecimal totalAmount) {
}
