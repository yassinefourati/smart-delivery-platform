package com.smartdelivery.inventory.dto;

import com.smartdelivery.inventory.domain.Inventory;

import java.util.UUID;

public record InventoryResponse(
        UUID id,
        UUID productId,
        UUID warehouseId,
        String warehouseName,
        int availableQuantity,
        int reservedQuantity
) {
    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getProductId(),
                inventory.getWarehouse().getId(),
                inventory.getWarehouse().getName(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity());
    }
}
