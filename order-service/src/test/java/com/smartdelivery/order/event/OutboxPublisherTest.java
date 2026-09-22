package com.smartdelivery.order.event;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single-instance behavior of the poller. The parts that only mean anything with a
 * real database -- the exclusivity of the claim, the per-aggregate ordering it enforces,
 * and the backoff actually keeping a row out of the next claim -- are covered by
 * {@code OutboxConcurrencyIntegrationTest} against a real Postgres, because a mocked
 * repository cannot lock a row.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    /** Stands in for the database-assigned sequence_no, which only a real insert sets. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private OutboxPublisher publisher() {
        var properties = new OutboxProperties(50, Duration.ofDays(7), 500, 20, INITIAL_BACKOFF, MAX_BACKOFF);
        return new OutboxPublisher(outboxEventRepository, kafkaTemplate, properties,
                new OutboxMetrics(meterRegistry, outboxEventRepository), transactionManager());
    }

    /**
     * The publisher's own transaction boundary is not what these tests are about -- the
     * integration tests exercise the real one. This stand-in just runs the callback.
     */
    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }

    private OutboxEvent pendingEvent(UUID aggregateId) {
        return pendingEvent(aggregateId, "{}");
    }

    private OutboxEvent pendingEvent(UUID aggregateId, String payload) {
        OutboxEvent event = new OutboxEvent("Order", aggregateId, "OrderCreated", KafkaTopics.ORDER_CREATED, payload);
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "sequenceNo", SEQUENCE.incrementAndGet());
        return event;
    }

    private OutboxEvent pendingEvent() {
        return pendingEvent(UUID.randomUUID());
    }

    private void claimReturns(OutboxEvent... events) {
        when(outboxEventRepository.claimNextBatch(any(Instant.class), eq(50))).thenReturn(List.of(events));
    }

    @SuppressWarnings("unchecked")
    private void kafkaSendSucceeds() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void kafkaSendFails() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);
    }

    @Test
    void publishPendingMarksARowPublishedOnASuccessfulSend() {
        OutboxEvent event = pendingEvent();
        claimReturns(event);
        kafkaSendSucceeds();

        publisher().publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
        assertThat(meterRegistry.counter(OutboxMetrics.PUBLISHED, "eventType", "OrderCreated").count()).isEqualTo(1);
    }

    @Test
    void publishPendingLeavesARowPendingAndRecordsTheErrorWhenTheSendFails() {
        OutboxEvent event = pendingEvent();
        claimReturns(event);
        kafkaSendFails();

        publisher().publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isNotNull();
        verify(outboxEventRepository).save(event);
        assertThat(meterRegistry.counter(OutboxMetrics.PUBLISH_FAILURES, "eventType", "OrderCreated").count())
                .isEqualTo(1);
    }

    @Test
    void aFailedSendPushesTheRowsNextAttemptOutByTheInitialBackoff() {
        OutboxEvent event = pendingEvent();
        claimReturns(event);
        kafkaSendFails();
        Instant beforePoll = Instant.now();

        publisher().publishPending();

        assertThat(event.getNextAttemptAt()).isAfterOrEqualTo(beforePoll.plus(INITIAL_BACKOFF));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailureDoesNotHoldBackAnotherAggregatesEvent() {
        OutboxEvent failing = pendingEvent();
        OutboxEvent other = pendingEvent();
        claimReturns(failing, other);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), eq(failing.getAggregateId().toString()), anyString()))
                .thenReturn(failed);
        when(kafkaTemplate.send(anyString(), eq(other.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher().publishPending();

        assertThat(failing.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(other.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    /**
     * The claim query only ever returns one row per aggregate, so this cannot happen
     * today -- which is exactly why it is worth pinning down: the loop's ordering
     * guarantee has to come from the loop, not from the shape of the query feeding it.
     */
    @Test
    void aFailedSendHoldsBackLaterEventsForTheSameAggregateInTheSameBatch() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent first = pendingEvent(aggregateId, "{\"seq\":1}");
        OutboxEvent second = pendingEvent(aggregateId, "{\"seq\":2}");
        claimReturns(first, second);
        kafkaSendFails();

        publisher().publishPending();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getAttempts()).isZero();
        // Never even offered to the broker, rather than offered and rejected.
        verify(kafkaTemplate, never()).send(anyString(), anyString(), eq(second.getPayload()));
        verify(outboxEventRepository, never()).save(second);
    }

    @Test
    void pollingAnEmptyOutboxSendsNothing() {
        when(outboxEventRepository.claimNextBatch(any(Instant.class), anyInt())).thenReturn(List.of());

        publisher().publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).save(any());
    }
}
