package com.smartdelivery.delivery.repository;

import com.smartdelivery.delivery.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByShipmentId(UUID shipmentId);

    List<Delivery> findByAgentId(UUID agentId);
}
