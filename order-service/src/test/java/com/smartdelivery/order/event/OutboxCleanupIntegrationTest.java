package com.smartdelivery.order.event;

import com.smartdelivery.platform.outbox.OutboxCleanupJob;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import com.smartdelivery.platform.outbox.OutboxMetrics;
import com.smartdelivery.platform.outbox.OutboxProperties;
import com.smartdelivery.platform.outbox.OutboxStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What OutboxCleanupJob deletes, and -- more to the point -- what it must not: a PENDING
 * row is never eligible however old it is, because age there means the event is stuck,
 * and deleting it would turn a visible backlog into exactly the silent event loss the
 * outbox exists to prevent (ADR 006).
 *
 * Rows are inserted through JDBC rather than the repository because these scenarios turn
 * on {@code published_at} values days in the past, which no amount of exercising the
 * real write path would produce.
 */
@SpringBootTest
@Testcontainers
class OutboxCleanupIntegrationTest {

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int CLEANUP_BATCH_SIZE = 5;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    /** Not used by these tests; the application context will not start without it. */
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // See OutboxConcurrencyIntegrationTest: parks the application's own scheduled
        // poller and cleanup job so they cannot act on this test's rows.
        registry.add("outbox.poll-interval-ms", () -> "3600000");
        registry.add("outbox.cleanup-interval-ms", () -> "3600000");
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void deletesPublishedRowsPastRetentionAndLeavesEverythingElseAlone() {
        UUID expired = insertPublished(Duration.ofDays(8));
        UUID recentlyPublished = insertPublished(Duration.ofDays(1));
        UUID stuck = insertPending(Duration.ofDays(30));

        job().deleteExpiredPublishedEvents();

        assertThat(existingIds()).containsExactlyInAnyOrder(recentlyPublished, stuck);
        assertThat(meterRegistry.counter(OutboxMetrics.CLEANUP_DELETED).count()).isEqualTo(1);
    }

    @Test
    void deletesInBoundedBatchesUntilNothingExpiredIsLeft() {
        for (int i = 0; i < CLEANUP_BATCH_SIZE * 3 + 2; i++) {
            insertPublished(Duration.ofDays(8));
        }
        UUID kept = insertPending(Duration.ofDays(30));

        job().deleteExpiredPublishedEvents();

        assertThat(existingIds()).containsExactly(kept);
    }

    /**
     * Two instances of the job running at the same time -- the ordinary case in a
     * replicated deployment. Neither may see the other's rows, so the two runs together
     * must account for each expired row exactly once, not twice.
     */
    @Test
    void twoConcurrentCleanupRunsDeleteEachExpiredRowExactlyOnce() throws Exception {
        int expiredRows = CLEANUP_BATCH_SIZE * 4;
        for (int i = 0; i < expiredRows; i++) {
            insertPublished(Duration.ofDays(8));
        }
        UUID kept = insertPending(Duration.ofDays(30));

        runConcurrently(job(), job());

        assertThat(existingIds()).containsExactly(kept);
        assertThat(meterRegistry.counter(OutboxMetrics.CLEANUP_DELETED).count()).isEqualTo(expiredRows);
    }

    private OutboxCleanupJob job() {
        var properties = new OutboxProperties(50, RETENTION, CLEANUP_BATCH_SIZE, 20,
                Duration.ofSeconds(2), Duration.ofMinutes(5));
        // One shared registry across both instances, so the assertion above is over the
        // total each of them claims to have deleted rather than over one of the two.
        return new OutboxCleanupJob(outboxEventRepository, properties,
                new OutboxMetrics(meterRegistry, outboxEventRepository), transactionManager);
    }

    private void runConcurrently(OutboxCleanupJob... jobs) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(jobs.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try {
            for (OutboxCleanupJob job : jobs) {
                runs.add(executor.submit(() -> {
                    start.await();
                    job.deleteExpiredPublishedEvents();
                    return null;
                }));
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID insertPublished(Duration age) {
        return insert(OutboxStatus.PUBLISHED, age, age);
    }

    private UUID insertPending(Duration age) {
        return insert(OutboxStatus.PENDING, age, null);
    }

    private UUID insert(OutboxStatus status, Duration age, Duration publishedAge) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, topic, payload,
                     status, attempts, created_at, published_at, next_attempt_at)
                VALUES (?, 'Order', ?, 'OrderCreated', ?, '{}', ?, 0, ?, ?, now())
                """,
                id, UUID.randomUUID(), KafkaTopics.ORDER_CREATED, status.name(),
                at(age), publishedAge == null ? null : at(publishedAge));
        return id;
    }

    private static OffsetDateTime at(Duration age) {
        return OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC);
    }

    private List<UUID> existingIds() {
        return jdbcTemplate.queryForList("SELECT id FROM outbox_events", UUID.class);
    }
}
