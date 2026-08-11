package com.smartdelivery.order.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Deliberately self-contained (see docs/kafka-events.md's "self-contained payloads"
 * design goal): a consumer like inventory-service can act on this without an extra
 * synchronous call back to order-service for the line items.
 */
public record OrderCreatedPayload(
        UUID orderId,
        UUID userId,
        List<OrderItemEventPayload> items,
        BigDecimal totalAmount
) {
}
