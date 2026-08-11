package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Inventory;
import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.dto.InventoryCreateRequest;
import com.smartdelivery.inventory.dto.InventoryResponse;
import com.smartdelivery.inventory.dto.InventorySummaryResponse;
import com.smartdelivery.inventory.exception.InventoryAlreadyExistsException;
import com.smartdelivery.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Warehouse-manager-facing operations: stocking a warehouse and reading the current
 * position. Reservation lifecycle (reserve/release/deduct) lives in
 * InventoryReservationService instead, since it has different transactional and
 * concurrency-retry needs -- see that class.
 */
@Service
public class InventoryAdminService {

    private final InventoryRepository inventoryRepository;
    private final WarehouseService warehouseService;

    public InventoryAdminService(InventoryRepository inventoryRepository, WarehouseService warehouseService) {
        this.inventoryRepository = inventoryRepository;
        this.warehouseService = warehouseService;
    }

    @Transactional
    public Inventory create(InventoryCreateRequest request) {
        inventoryRepository.findByProductIdAndWarehouseId(request.productId(), request.warehouseId())
                .ifPresent(existing -> {
                    throw new InventoryAlreadyExistsException(request.productId(), request.warehouseId());
                });

        Warehouse warehouse = warehouseService.getById(request.warehouseId());
        Inventory inventory = new Inventory(request.productId(), warehouse, request.availableQuantity());
        return inventoryRepository.save(inventory);
    }

    @Transactional(readOnly = true)
    public InventorySummaryResponse getSummary(UUID productId) {
        var rows = inventoryRepository.findByProductId(productId).stream()
                .map(InventoryResponse::from)
                .toList();
        return InventorySummaryResponse.from(productId, rows);
    }
}
