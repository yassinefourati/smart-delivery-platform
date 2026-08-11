package com.smartdelivery.delivery.event;

import java.util.UUID;

/**
 * {@code agentId} is this service's own DeliveryAgent id, not the agent's user-service
 * userId -- order-service (the only consumer today) doesn't use it, but a future
 * consumer needing to identify the agent unambiguously would need this service's id,
 * the same self-contained-payload reasoning as {@code shipmentId}. Must match
 * order-service's local copy field-for-field -- see docs/kafka-events.md.
 */
public record DeliveryAssignedPayload(UUID orderId, UUID shipmentId, UUID agentId) {
}
