package com.smartdelivery.payment.event;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Micrometer instrumentation for the outbox (ADR 006), scraped by Prometheus and
 * rendered on the platform-overview Grafana dashboard -- see docs/observability.md.
 *
 * The counters say what the poller did; the gauges say whether it is keeping up, which
 * is the question that actually matters and the one nothing could answer before this
 * phase. A non-zero pending count is normal -- rows land continuously and drain within
 * a poll interval -- so the alertable signal is a pending count that only grows, or an
 * oldest-pending age well past the poll interval. Either means events are committed but
 * stuck, which downstream looks exactly like a silently stalled saga.
 */
@Component
public class OutboxMetrics {

    static final String PENDING_COUNT = "outbox.pending.count";
    static final String OLDEST_PENDING_AGE = "outbox.oldest.pending.age.seconds";
    static final String PUBLISHED = "outbox.published";
    static final String PUBLISH_FAILURES = "outbox.publish.failures";
    static final String CLEANUP_DELETED = "outbox.cleanup.deleted";

    private final MeterRegistry meterRegistry;

    public OutboxMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.meterRegistry = meterRegistry;
        // Evaluated at scrape time rather than cached on each poll: both queries are
        // served by the partial PENDING index, and a gauge that lags the poll interval
        // would understate exactly the backlog it exists to expose.
        Gauge.builder(PENDING_COUNT, outboxEventRepository, repository -> repository.countByStatus(OutboxStatus.PENDING))
                .description("Outbox rows written but not yet published to Kafka")
                .register(meterRegistry);
        Gauge.builder(OLDEST_PENDING_AGE, outboxEventRepository, OutboxMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished outbox row (0 when there are none)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /**
     * Tagged by event type, not just counted, so a failure confined to one topic (a
     * broker-side rejection of one payload shape, say) is distinguishable from the
     * whole broker being unreachable.
     */
    public void recordPublished(String eventType) {
        meterRegistry.counter(PUBLISHED, "eventType", eventType).increment();
    }

    public void recordPublishFailure(String eventType) {
        meterRegistry.counter(PUBLISH_FAILURES, "eventType", eventType).increment();
    }

    public void recordCleanupDeleted(int rows) {
        meterRegistry.counter(CLEANUP_DELETED).increment(rows);
    }

    private static double oldestPendingAgeSeconds(OutboxEventRepository outboxEventRepository) {
        return outboxEventRepository.findOldestCreatedAt(OutboxStatus.PENDING)
                .map(createdAt -> (double) Duration.between(createdAt, Instant.now()).toSeconds())
                .orElse(0.0);
    }
}
