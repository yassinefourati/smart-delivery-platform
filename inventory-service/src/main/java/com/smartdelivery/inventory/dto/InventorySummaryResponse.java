package com.smartdelivery.inventory.dto;

import java.util.List;
import java.util.UUID;

public record InventorySummaryResponse(
        UUID productId,
        int totalAvailable,
        int totalReserved,
        List<InventoryResponse> warehouses
) {
    public static InventorySummaryResponse from(UUID productId, List<InventoryResponse> warehouses) {
        int totalAvailable = warehouses.stream().mapToInt(InventoryResponse::availableQuantity).sum();
        int totalReserved = warehouses.stream().mapToInt(InventoryResponse::reservedQuantity).sum();
        return new InventorySummaryResponse(productId, totalAvailable, totalReserved, warehouses);
    }
}
