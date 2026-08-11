package com.smartdelivery.inventory.exception;

import java.util.UUID;

public class InventoryAlreadyExistsException extends RuntimeException {

    public InventoryAlreadyExistsException(UUID productId, UUID warehouseId) {
        super("Inventory for product '%s' already exists at warehouse '%s'".formatted(productId, warehouseId));
    }
}
