package com.smartdelivery.delivery.repository;

import com.smartdelivery.delivery.domain.DeliveryAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryAgentRepository extends JpaRepository<DeliveryAgent, UUID> {

    Optional<DeliveryAgent> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
