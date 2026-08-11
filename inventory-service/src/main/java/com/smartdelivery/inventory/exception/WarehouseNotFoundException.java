package com.smartdelivery.inventory.exception;

import java.util.UUID;

public class WarehouseNotFoundException extends RuntimeException {

    public WarehouseNotFoundException(UUID warehouseId) {
        super("Warehouse '%s' was not found".formatted(warehouseId));
    }
}
