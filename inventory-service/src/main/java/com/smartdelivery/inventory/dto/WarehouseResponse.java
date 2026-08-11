package com.smartdelivery.inventory.dto;

import com.smartdelivery.inventory.domain.Warehouse;

import java.util.UUID;

public record WarehouseResponse(
        UUID id,
        String name,
        String location,
        boolean active
) {
    public static WarehouseResponse from(Warehouse warehouse) {
        return new WarehouseResponse(warehouse.getId(), warehouse.getName(), warehouse.getLocation(), warehouse.isActive());
    }
}
