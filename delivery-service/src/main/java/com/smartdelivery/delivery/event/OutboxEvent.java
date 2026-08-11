package com.smartdelivery.delivery.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * A row written in the same transaction as the business change it announces (ADR 004).
 * {@link OutboxPublisher} polls {@code PENDING} rows on a schedule and actually sends
 * them to Kafka in a separate transaction -- see that class for why splitting "record
 * the fact" from "publish the fact" this way is what makes the two atomic with the
 * business change instead of with each other.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    /** The fully serialized {@link EventEnvelope} JSON -- published verbatim, unchanged. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String topic, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /** Left {@code PENDING} so the next poll retries it -- see OutboxPublisher. */
    public void recordFailedAttempt(String error) {
        this.attempts++;
        this.lastError = error;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
