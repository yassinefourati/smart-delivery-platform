package com.smartdelivery.inventory.repository;

import com.smartdelivery.inventory.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    List<Inventory> findByProductId(UUID productId);

    /**
     * Warehouses with enough available stock to satisfy a reservation alone, most
     * available-first. See InventoryReservationService for why we deliberately do not
     * split a single reservation across multiple warehouses.
     */
    List<Inventory> findByProductIdAndAvailableQuantityGreaterThanEqualOrderByAvailableQuantityDesc(
            UUID productId, int quantity);

    Optional<Inventory> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);
}
