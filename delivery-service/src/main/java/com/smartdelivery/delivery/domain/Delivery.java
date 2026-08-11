package com.smartdelivery.delivery.domain;

import com.smartdelivery.delivery.exception.InvalidDeliveryStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One agent's assignment to one shipment -- unique on {@code shipmentId} (this phase
 * does not support reassignment; see {@code DeliveryService.assign}'s Javadoc). Created
 * the moment an admin assigns an agent, completed when that agent marks it done.
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "shipment_id", nullable = false, unique = true)
    private UUID shipmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private DeliveryAgent agent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Delivery() {
    }

    public Delivery(UUID shipmentId, DeliveryAgent agent) {
        this.shipmentId = shipmentId;
        this.agent = agent;
        this.status = DeliveryStatus.ASSIGNED;
        this.assignedAt = Instant.now();
    }

    public void complete() {
        if (status != DeliveryStatus.ASSIGNED) {
            throw new InvalidDeliveryStateException("complete", status);
        }
        status = DeliveryStatus.COMPLETED;
        deliveredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public DeliveryAgent getAgent() {
        return agent;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
