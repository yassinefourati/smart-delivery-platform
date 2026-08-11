package com.smartdelivery.inventory.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Identifies an existing reservation for /release and /deduct -- both key off (orderId, productId). */
public record ReservationLookupRequest(
        @NotNull UUID orderId,
        @NotNull UUID productId
) {
}
