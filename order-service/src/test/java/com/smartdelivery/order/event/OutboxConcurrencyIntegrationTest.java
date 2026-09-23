package com.smartdelivery.order.event;

import com.smartdelivery.platform.outbox.OutboxEvent;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import com.smartdelivery.platform.outbox.OutboxMetrics;
import com.smartdelivery.platform.outbox.OutboxProperties;
import com.smartdelivery.platform.outbox.OutboxPublisher;
import com.smartdelivery.platform.outbox.OutboxStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the outbox's Phase 15 behavior (ADR 006) that a mocked repository cannot
 * express, because all three guarantees are properties of what the database does under
 * concurrency rather than of what the Java does: that two publisher instances polling
 * one table publish every row exactly once, that a failed send stops the rest of that
 * aggregate's stream rather than being stepped over, and that a failing row really is
 * left alone until its backoff elapses.
 *
 * Events are written to a topic of this test's own rather than to a real one from the
 * catalog, so that order-service's own listeners -- which would otherwise start a saga
 * off every row this test writes -- have nothing to react to.
 *
 * The publishers under test are constructed here rather than autowired, because the
 * scenarios need two of them at once and need one of them to fail on demand. The
 * application's own scheduled instance is parked for the duration (see
 * {@code outbox.poll-interval-ms} below) so it cannot claim rows out from under them.
 */
@SpringBootTest
@Testcontainers
class OutboxConcurrencyIntegrationTest {

    private static final String TOPIC = "outbox-it.events";
    private static final int AGGREGATES = 8;
    private static final int EVENTS_PER_AGGREGATE = 5;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Parks the application's own poller and cleanup job for the whole run. The
        // interval alone is not enough: a fixedDelay schedule's first run fires at startup
        // whatever the interval, and on a slow runner that run can land while a test is
        // still writing its rows. Without both they race every test in this class.
        registry.add("outbox.poll-interval-ms", () -> "3600000");
        registry.add("outbox.cleanup-interval-ms", () -> "3600000");
        registry.add("outbox.poll-initial-delay-ms", () -> "3600000");
        registry.add("outbox.cleanup-initial-delay-ms", () -> "3600000");
    }

    /**
     * Created up front rather than left to Kafka's auto-creation: a consumer that
     * subscribes to a topic which does not exist yet only notices it appearing when its
     * metadata next expires, which by default is five minutes away.
     */
    @BeforeAll
    static void createTestTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        testConsumer = new KafkaConsumer<>(consumerProperties());
        testConsumer.subscribe(List.of(TOPIC));
        // Force the subscription to take effect now, so nothing published by the test
        // body lands before this consumer has been assigned its partitions.
        testConsumer.poll(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        testConsumer.close();
    }

    @Test
    void twoPublishersPollingOneTablePublishEveryEventExactlyOnceAndInOrderPerAggregate() throws Exception {
        List<UUID> aggregates = new ArrayList<>();
        for (int i = 0; i < AGGREGATES; i++) {
            aggregates.add(UUID.randomUUID());
        }
        // Interleaved rather than aggregate-by-aggregate: an aggregate's events are far
        // apart in sequence_no, so getting them out in order is a real result and not an
        // artifact of them happening to have been written consecutively.
        for (int sequence = 1; sequence <= EVENTS_PER_AGGREGATE; sequence++) {
            for (UUID aggregateId : aggregates) {
                writeEvent(aggregateId, String.valueOf(sequence));
            }
        }

        drainConcurrently(publisher(kafkaTemplate), publisher(kafkaTemplate));

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
        Map<String, List<String>> published = consumeAll();
        assertThat(published).hasSize(AGGREGATES);
        assertThat(published.values()).allSatisfy(payloads ->
                assertThat(payloads).containsExactly("1", "2", "3", "4", "5"));
    }

    @Test
    void aSendFailureHoldsBackTheRestOfThatAggregatesStreamButNotAnotherAggregates() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        writeEvent(failing, "1");
        writeEvent(healthy, "1");
        writeEvent(failing, "2");

        // A backoff longer than the test can possibly take, so the second poll skipping
        // the failed row is the claim query's doing and not a race with a 2s retry.
        OutboxPublisher publisher =
                publisher(kafkaTemplateFailingFor(Set.of(failing.toString())), Duration.ofMinutes(30));
        publisher.publishPending();
        publisher.publishPending();

        Map<String, List<String>> published = consumeAll();
        // The other aggregate went through on the first poll, unaffected.
        assertThat(published).containsOnlyKeys(healthy.toString());
        assertThat(published.get(healthy.toString())).containsExactly("1");
        // The failing aggregate's second event was never even offered to the broker: it
        // is not eligible to be claimed while its predecessor is unpublished.
        assertThat(payloadsOf(failing, OutboxStatus.PENDING)).containsExactly("1", "2");
        assertThat(attemptsOf(failing)).containsExactly(1, 0);

        // Once the send works again, both arrive, in order.
        makeEligibleNow(failing);
        drain(publisher(kafkaTemplate));

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
        assertThat(consumeAll().get(failing.toString())).containsExactly("1", "2");
    }

    @Test
    void aFailedRowIsNotRetriedBeforeItsBackoffElapses() {
        UUID aggregateId = UUID.randomUUID();
        writeEvent(aggregateId, "1");

        // A backoff far longer than this test's runtime, so "not retried yet" cannot be
        // a timing coincidence.
        Duration backoff = Duration.ofMinutes(30);
        publisher(kafkaTemplateFailingFor(Set.of(aggregateId.toString())), backoff).publishPending();

        Instant nextAttemptAt = nextAttemptAtOf(aggregateId);
        assertThat(nextAttemptAt).isAfter(Instant.now().plus(Duration.ofMinutes(25)));

        // A healthy publisher now: the row stays unpublished because of the backoff, not
        // because sending is still broken.
        OutboxPublisher healthyPublisher = publisher(kafkaTemplate);
        healthyPublisher.publishPending();
        healthyPublisher.publishPending();

        assertThat(consumeAll()).isEmpty();
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);

        // ... and it is published as soon as that backoff is behind it.
        makeEligibleNow(aggregateId);
        drain(healthyPublisher);

        assertThat(consumeAll().get(aggregateId.toString())).containsExactly("1");
    }

    private OutboxPublisher publisher(KafkaTemplate<String, String> template) {
        return publisher(template, Duration.ofSeconds(2));
    }

    private OutboxPublisher publisher(KafkaTemplate<String, String> template, Duration initialBackoff) {
        var properties = new OutboxProperties(50, Duration.ofDays(7), 500, 20, initialBackoff, Duration.ofMinutes(30));
        return new OutboxPublisher(outboxEventRepository, template, properties,
                new OutboxMetrics(new SimpleMeterRegistry(), outboxEventRepository), transactionManager);
    }

    /** A real template for every key but the named ones, which never reach the broker. */
    private KafkaTemplate<String, String> kafkaTemplateFailingFor(Set<String> keys) {
        return new KafkaTemplate<>(kafkaTemplate.getProducerFactory()) {
            @Override
            public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
                if (keys.contains(key)) {
                    CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new IllegalStateException("broker unavailable"));
                    return failed;
                }
                return super.send(topic, key, data);
            }
        };
    }

    private void writeEvent(UUID aggregateId, String payload) {
        outboxEventRepository.save(new OutboxEvent("Order", aggregateId, "OrderCreated", TOPIC, payload));
    }

    /** Stands in for waiting out a backoff, which is otherwise the test's whole runtime. */
    private void makeEligibleNow(UUID aggregateId) {
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() WHERE aggregate_id = ?", aggregateId);
    }

    private List<String> payloadsOf(UUID aggregateId, OutboxStatus status) {
        return jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND status = ? ORDER BY sequence_no",
                String.class, aggregateId, status.name());
    }

    private List<Integer> attemptsOf(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT attempts FROM outbox_events WHERE aggregate_id = ? ORDER BY sequence_no",
                Integer.class, aggregateId);
    }

    private Instant nextAttemptAtOf(UUID aggregateId) {
        OffsetDateTime nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM outbox_events WHERE aggregate_id = ?",
                OffsetDateTime.class, aggregateId);
        return nextAttemptAt == null ? null : nextAttemptAt.toInstant();
    }

    private void drain(OutboxPublisher publisher) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (outboxEventRepository.countByStatus(OutboxStatus.PENDING) > 0 && System.currentTimeMillis() < deadline) {
            publisher.publishPending();
        }
    }

    private void drainConcurrently(OutboxPublisher... publishers) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(publishers.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> drains = new ArrayList<>();
        try {
            for (OutboxPublisher publisher : publishers) {
                drains.add(executor.submit(() -> {
                    start.await();
                    drain(publisher);
                    return null;
                }));
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            for (Future<?> drained : drains) {
                drained.get();  // rethrows anything a publisher thread threw
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("a publisher thread failed", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Every record on the test topic, grouped by key in arrival order. Keeps polling past
     * the last record it saw, so a row published twice shows up as a duplicate here
     * rather than being missed by a consumer that stopped as soon as the expected count
     * was reached.
     */
    private Map<String, List<String>> consumeAll() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (int emptyPolls = 0; emptyPolls < 3; ) {
            ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
            if (records.isEmpty()) {
                emptyPolls++;
                continue;
            }
            emptyPolls = 0;
            for (ConsumerRecord<String, String> record : records) {
                byKey.computeIfAbsent(record.key(), key -> new ArrayList<>()).add(record.value());
            }
        }
        return byKey;
    }

    private static Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 500);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }
}
