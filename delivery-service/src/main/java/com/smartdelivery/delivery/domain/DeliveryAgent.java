package com.smartdelivery.delivery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code userId} is the id of a user-service {@code User} who holds the
 * {@code DELIVERY_AGENT} role -- delivery-service does not call back to user-service to
 * verify that (no synchronous dependency exists between the two today), the same
 * documented trust boundary order-service already has toward the {@code userId} it
 * reads out of a JWT. An admin is trusted to link the right user when creating this
 * record. It's what {@link com.smartdelivery.delivery.web.DeliveryController}'s
 * ownership checks compare against the JWT subject for -- see docs/security.md.
 */
@Entity
@Table(name = "delivery_agents")
public class DeliveryAgent {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 30)
    private String phone;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeliveryAgent() {
    }

    public DeliveryAgent(UUID userId, String name, String phone) {
        this.userId = userId;
        this.name = name;
        this.phone = phone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
