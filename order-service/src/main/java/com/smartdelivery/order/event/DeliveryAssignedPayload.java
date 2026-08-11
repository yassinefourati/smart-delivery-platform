package com.smartdelivery.order.event;

import java.util.UUID;

/**
 * shipmentId is delivery-service's identifier, not an order-service concept -- order
 * ownership is resolved via orderId, which delivery-service must include for exactly
 * this reason (see docs/kafka-events.md's self-contained-payload design goal).
 */
public record DeliveryAssignedPayload(UUID orderId, UUID shipmentId, UUID agentId) {
}
