package com.smartdelivery.inventory.repository;

import com.smartdelivery.inventory.domain.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    Optional<InventoryReservation> findByOrderIdAndProductId(UUID orderId, UUID productId);
}
