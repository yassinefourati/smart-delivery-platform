package com.smartdelivery.delivery.dto;

import com.smartdelivery.delivery.domain.Shipment;
import com.smartdelivery.delivery.domain.ShipmentStatus;

import java.time.Instant;
import java.util.UUID;

public record ShipmentResponse(
        UUID id,
        UUID orderId,
        ShipmentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(), shipment.getOrderId(), shipment.getStatus(),
                shipment.getCreatedAt(), shipment.getUpdatedAt());
    }
}
