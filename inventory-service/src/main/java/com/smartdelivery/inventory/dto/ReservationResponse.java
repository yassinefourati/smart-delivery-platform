package com.smartdelivery.inventory.dto;

import com.smartdelivery.inventory.domain.InventoryReservation;
import com.smartdelivery.inventory.domain.ReservationStatus;

import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID orderId,
        UUID productId,
        int quantity,
        ReservationStatus status
) {
    public static ReservationResponse from(InventoryReservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getProductId(),
                reservation.getQuantity(),
                reservation.getStatus());
    }
}
