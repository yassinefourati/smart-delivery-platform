package com.smartdelivery.order.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for the outbox poller and its cleanup job (ADR 006).
 *
 * {@code outbox.poll-interval-ms} and {@code outbox.cleanup-interval-ms} are
 * deliberately absent: {@code @Scheduled} resolves those from the environment as
 * placeholder strings at bean-definition time, before any binding of this record could
 * have happened, so having them here too would be two sources of truth for one value.
 *
 * @param batchSize              rows claimed per poll. Each is sent to Kafka inside the
 *                               claiming transaction, so this bounds how long that
 *                               transaction can hold its row locks -- see OutboxPublisher.
 * @param retention              how long a published row is kept before OutboxCleanupJob
 *                               deletes it. Long enough to still be useful for
 *                               after-the-fact "was this event really published?"
 *                               debugging; short enough that the table does not grow
 *                               without bound.
 * @param cleanupBatchSize       rows deleted per statement, each in its own transaction.
 * @param cleanupMaxBatchesPerRun a ceiling on one cleanup run, so a first run against a
 *                               long-neglected table does a bounded amount of work and
 *                               finishes the rest on the next run, instead of churning
 *                               for an unbounded time.
 * @param initialBackoff         delay before the first retry of a failed row.
 * @param maxBackoff             cap on that delay, however many times a row has failed.
 */
@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("500") int cleanupBatchSize,
        @DefaultValue("20") int cleanupMaxBatchesPerRun,
        @DefaultValue("2s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff) {
}
