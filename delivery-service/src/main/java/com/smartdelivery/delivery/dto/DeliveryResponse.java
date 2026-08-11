package com.smartdelivery.delivery.dto;

import com.smartdelivery.delivery.domain.Delivery;
import com.smartdelivery.delivery.domain.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        UUID shipmentId,
        UUID agentId,
        DeliveryStatus status,
        Instant assignedAt,
        Instant deliveredAt
) {
    public static DeliveryResponse from(Delivery delivery) {
        return new DeliveryResponse(
                delivery.getId(), delivery.getShipmentId(), delivery.getAgent().getId(),
                delivery.getStatus(), delivery.getAssignedAt(), delivery.getDeliveredAt());
    }
}
