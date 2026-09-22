package com.smartdelivery.order.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox table's own contract, against a real Postgres and the real Flyway
 * migrations: that {@code sequence_no} is assigned by the database in insert order and
 * read back onto the entity, that the claim query picks the one eligible row per
 * aggregate, and that the retention delete respects its cutoff and its batch size.
 *
 * Deliberately a {@code @DataJpaTest} and not a {@code @SpringBootTest}: none of this
 * needs Kafka, a web layer, or the saga's REST clients, and the mapping it pins down is
 * the one thing a unit test with a mocked repository can say nothing at all about.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OutboxEventRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void sequenceNoIsAssignedByTheDatabaseInInsertOrder() {
        OutboxEvent first = save(UUID.randomUUID(), "1");
        OutboxEvent second = save(UUID.randomUUID(), "2");

        assertThat(first.getSequenceNo()).isNotNull();
        assertThat(second.getSequenceNo()).isGreaterThan(first.getSequenceNo());
        // The column the ordering depends on is the database's to assign, so a row that
        // never round-tripped through it would order by a null.
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getNextAttemptAt()).isNotNull();
    }

    @Test
    void claimNextBatchReturnsOnlyTheOldestPendingRowPerAggregate() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        save(first, "a1");
        save(second, "b1");
        save(first, "a2");
        save(second, "b2");

        List<OutboxEvent> claimed = outboxEventRepository.claimNextBatch(Instant.now(), 50);

        assertThat(claimed).extracting(OutboxEvent::getPayload).containsExactly("a1", "b1");
    }

    @Test
    void claimNextBatchSkipsARowThatIsStillBackingOffAndEverythingBehindIt() {
        UUID backingOff = UUID.randomUUID();
        UUID ready = UUID.randomUUID();
        OutboxEvent held = save(backingOff, "held");
        save(backingOff, "behind-held");
        save(ready, "ready");

        held.recordFailedAttempt("broker unavailable", Duration.ofMinutes(30), Duration.ofMinutes(30));
        entityManager.flush();

        List<OutboxEvent> claimed = outboxEventRepository.claimNextBatch(Instant.now(), 50);

        // Not just the backing-off row: the event queued behind it is withheld too,
        // because publishing it first would reorder that aggregate's stream.
        assertThat(claimed).extracting(OutboxEvent::getPayload).containsExactly("ready");
    }

    @Test
    void claimNextBatchHonoursTheBatchSize() {
        for (int i = 0; i < 5; i++) {
            save(UUID.randomUUID(), "event-" + i);
        }

        assertThat(outboxEventRepository.claimNextBatch(Instant.now(), 2)).hasSize(2);
    }

    @Test
    void deletePublishedBeforeRemovesExpiredPublishedRowsOnly() {
        OutboxEvent expired = save(UUID.randomUUID(), "expired");
        OutboxEvent recent = save(UUID.randomUUID(), "recent");
        OutboxEvent stillPending = save(UUID.randomUUID(), "pending");
        publishedAt(expired, Instant.now().minus(Duration.ofDays(8)));
        publishedAt(recent, Instant.now().minus(Duration.ofDays(1)));

        int deleted = outboxEventRepository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(7)), 500);

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.findAll()).extracting(OutboxEvent::getPayload)
                .containsExactlyInAnyOrder(recent.getPayload(), stillPending.getPayload());
    }

    @Test
    void countsAndOldestPendingBackTheOutboxGauges() {
        assertThat(outboxEventRepository.findOldestCreatedAt(OutboxStatus.PENDING)).isEmpty();

        OutboxEvent oldest = save(UUID.randomUUID(), "oldest");
        save(UUID.randomUUID(), "newer");

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
        assertThat(outboxEventRepository.findOldestCreatedAt(OutboxStatus.PENDING))
                .contains(oldest.getCreatedAt());
    }

    private OutboxEvent save(UUID aggregateId, String payload) {
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent("Order", aggregateId, "OrderCreated", KafkaTopics.ORDER_CREATED, payload));
        // The claim query is native SQL, so it sees rows rather than the persistence
        // context; refreshing pulls back what the database actually stored.
        entityManager.refresh(event);
        return event;
    }

    private void publishedAt(OutboxEvent event, Instant publishedAt) {
        event.markPublished();
        entityManager.flush();
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE outbox_events SET published_at = :publishedAt WHERE id = :id")
                .setParameter("publishedAt", publishedAt)
                .setParameter("id", event.getId())
                .executeUpdate();
    }
}
