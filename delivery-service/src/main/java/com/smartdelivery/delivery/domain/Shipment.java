package com.smartdelivery.delivery.domain;

import com.smartdelivery.delivery.exception.InvalidShipmentStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Created when {@code payment.completed} arrives for an order (see
 * PaymentCompletedListener) -- one per order, unique on {@code orderId}. Its status
 * mirrors the {@link Delivery} record created when it's assigned, kept in sync here so
 * a caller can query shipment state without joining to Delivery -- the same
 * denormalized-for-querying trade-off Order makes for its own status.
 */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shipment() {
    }

    public Shipment(UUID orderId) {
        this.orderId = orderId;
        this.status = ShipmentStatus.CREATED;
    }

    public void markAssigned() {
        if (status != ShipmentStatus.CREATED) {
            throw new InvalidShipmentStateException("assign", status);
        }
        status = ShipmentStatus.ASSIGNED;
    }

    public void markDelivered() {
        if (status != ShipmentStatus.ASSIGNED) {
            throw new InvalidShipmentStateException("deliver", status);
        }
        status = ShipmentStatus.DELIVERED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
