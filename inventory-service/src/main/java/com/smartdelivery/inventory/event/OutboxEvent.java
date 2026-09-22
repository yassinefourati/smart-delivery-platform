package com.smartdelivery.inventory.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A row written in the same transaction as the business change it announces (ADR 004).
 * {@link OutboxPublisher} claims {@code PENDING} rows on a schedule and actually sends
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

    /**
     * Database-assigned publish order for this service's outbox (ADR 006). Deliberately
     * not {@code createdAt}: two rows written in the same transaction routinely share a
     * creation timestamp, and a tie makes "the oldest unpublished event for this
     * aggregate" ambiguous -- the one question the claim query must answer
     * unambiguously for an aggregate's events to reach Kafka in order.
     *
     * Assigned by the column's own sequence default, not by Hibernate, so that the
     * ordering is decided at {@code INSERT} time by the database rather than by
     * whichever instance happened to build the entity.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "sequence_no", insertable = false, updatable = false)
    private Long sequenceNo;

    /**
     * Earliest time {@link OutboxPublisher} may try this row again. See
     * {@link #recordFailedAttempt}.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

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
        // Eligible immediately: a brand-new row has nothing to back off from.
        this.nextAttemptAt = Instant.now();
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /**
     * Left {@code PENDING} so a later poll retries it -- indefinitely, as ADR 004 says
     * -- but no longer on the very next poll: {@code nextAttemptAt} moves out
     * exponentially, so a row that can never be published (an unroutable topic, a
     * payload the broker rejects) stops costing a Kafka round trip every two seconds
     * forever, and stops crowding out healthy rows in each claimed batch.
     *
     * Still unbounded, and still no dead-letter state, because abandoning a row would
     * silently drop an event whose business change is already committed -- the exact
     * outcome the outbox exists to prevent. The cap keeps a poison row cheap instead of
     * giving up on it.
     */
    public void recordFailedAttempt(String error, Duration initialBackoff, Duration maxBackoff) {
        this.attempts++;
        this.lastError = error;
        this.nextAttemptAt = Instant.now().plus(backoffFor(this.attempts, initialBackoff, maxBackoff));
    }

    /**
     * {@code initialBackoff}, doubled per attempt, clamped to {@code maxBackoff}.
     * Doubling in a loop that stops at the cap rather than computing
     * {@code initial * 2^(attempts-1)} directly, because {@code attempts} is unbounded
     * and that shift overflows long well before the retries stop.
     */
    static Duration backoffFor(int attempts, Duration initialBackoff, Duration maxBackoff) {
        Duration backoff = initialBackoff;
        for (int attempt = 1; attempt < attempts && backoff.compareTo(maxBackoff) < 0; attempt++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
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

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
