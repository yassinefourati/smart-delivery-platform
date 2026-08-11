package com.smartdelivery.notification.event;

import java.util.UUID;

public record DeliveryAssignedPayload(UUID orderId, UUID shipmentId, UUID agentId) {
}
