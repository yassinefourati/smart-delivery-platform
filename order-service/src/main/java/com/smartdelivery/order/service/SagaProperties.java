package com.smartdelivery.order.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for {@link StuckSagaReaper} (ADR 008).
 *
 * {@code saga.reaper-interval-ms} is deliberately absent: {@code @Scheduled} resolves it
 * from the environment as a placeholder string at bean-definition time, before any
 * binding of this record could have happened, so having it here too would be two sources
 * of truth for one value.
 *
 * @param stuckThreshold how long an order may sit untouched in a resumable state before
 *                       the reaper treats its saga as stuck. Has to be comfortably longer
 *                       than a healthy saga takes end to end -- including Spring Kafka's
 *                       own three retries -- or the reaper races the saga it is meant to
 *                       rescue. It is also the lease the reaper's claim takes out on an
 *                       order, so it bounds how long a crashed reaper can strand one.
 * @param maxAttempts    how many times the reaper re-runs a saga before giving up on it
 *                       and failing the order. Counted per order, not per reaper run.
 * @param batchSize      orders claimed per run.
 */
@ConfigurationProperties(prefix = "saga")
public record SagaProperties(
        @DefaultValue("5m") Duration stuckThreshold,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("50") int batchSize) {
}
