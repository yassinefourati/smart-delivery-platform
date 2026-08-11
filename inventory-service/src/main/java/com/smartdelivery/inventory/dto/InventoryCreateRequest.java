package com.smartdelivery.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record InventoryCreateRequest(
        @NotNull UUID productId,
        @NotNull UUID warehouseId,
        @Min(0) int availableQuantity
) {
}
