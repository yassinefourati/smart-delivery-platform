package com.smartdelivery.inventory.repository;

import com.smartdelivery.inventory.domain.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
