package com.smartdelivery.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per (productId, warehouse) pair. {@link #version} is the optimistic lock
 * that prevents overselling under concurrent reservations -- see V2__create_inventory.sql
 * and InventoryReservationService.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {
    }

    public Inventory(UUID productId, Warehouse warehouse, int availableQuantity) {
        this.productId = productId;
        this.warehouse = warehouse;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Moves {@code quantity} units from available to reserved. Throws if the row does
     * not currently hold enough available stock -- callers must not have decided to
     * call this before checking, since the check-and-decrement has to be atomic with
     * respect to this entity's in-memory state within the enclosing transaction.
     */
    public void reserve(int quantity) {
        if (availableQuantity < quantity) {
            throw new IllegalStateException(
                    "Cannot reserve %d units: only %d available".formatted(quantity, availableQuantity));
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    /** Reverses a reservation: moves {@code quantity} units back from reserved to available. */
    public void release(int quantity) {
        reservedQuantity -= quantity;
        availableQuantity += quantity;
    }

    /** Permanently commits a reservation: removes {@code quantity} from reserved for good. */
    public void deduct(int quantity) {
        reservedQuantity -= quantity;
    }
}
