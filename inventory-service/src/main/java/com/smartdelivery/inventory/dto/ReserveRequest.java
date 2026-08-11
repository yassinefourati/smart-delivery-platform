package com.smartdelivery.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID orderId,
        @NotNull UUID productId,
        @Min(1) int quantity
) {
}
