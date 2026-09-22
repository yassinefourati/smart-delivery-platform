package com.smartdelivery.order.event;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pacing and the retention cutoff. That the delete itself never touches a PENDING row,
 * and is safe to run from two instances at once, is a property of the SQL rather than of
 * this class -- see {@code OutboxCleanupIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxCleanupJobTest {

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int CLEANUP_BATCH_SIZE = 500;
    private static final int MAX_BATCHES = 20;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private OutboxCleanupJob job() {
        var properties = new OutboxProperties(50, RETENTION, CLEANUP_BATCH_SIZE, MAX_BATCHES,
                Duration.ofSeconds(2), Duration.ofMinutes(5));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new OutboxCleanupJob(outboxEventRepository, properties,
                new OutboxMetrics(meterRegistry, outboxEventRepository), transactionManager);
    }

    @Test
    void deletesPublishedRowsOlderThanTheRetentionPeriod() {
        when(outboxEventRepository.deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE))).thenReturn(3);
        Instant beforeRun = Instant.now();

        job().deleteExpiredPublishedEvents();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outboxEventRepository).deletePublishedBefore(cutoff.capture(), eq(CLEANUP_BATCH_SIZE));
        assertThat(cutoff.getValue())
                .isBetween(beforeRun.minus(RETENTION), Instant.now().minus(RETENTION));
        assertThat(meterRegistry.counter(OutboxMetrics.CLEANUP_DELETED).count()).isEqualTo(3);
    }

    @Test
    void keepsDeletingWhileEachBatchComesBackFull() {
        when(outboxEventRepository.deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE)))
                .thenReturn(CLEANUP_BATCH_SIZE, CLEANUP_BATCH_SIZE, 7);

        job().deleteExpiredPublishedEvents();

        verify(outboxEventRepository, times(3)).deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE));
        assertThat(meterRegistry.counter(OutboxMetrics.CLEANUP_DELETED).count())
                .isEqualTo(2.0 * CLEANUP_BATCH_SIZE + 7);
    }

    /** A long-neglected table finishes on the next run rather than churning for hours on this one. */
    @Test
    void stopsAfterTheConfiguredNumberOfBatchesEvenIfMoreRowsAreExpired() {
        when(outboxEventRepository.deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE)))
                .thenReturn(CLEANUP_BATCH_SIZE);

        job().deleteExpiredPublishedEvents();

        verify(outboxEventRepository, times(MAX_BATCHES))
                .deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE));
    }

    @Test
    void doesNothingWhenNoRowsHaveExpired() {
        when(outboxEventRepository.deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE))).thenReturn(0);

        job().deleteExpiredPublishedEvents();

        verify(outboxEventRepository).deletePublishedBefore(any(Instant.class), eq(CLEANUP_BATCH_SIZE));
        assertThat(meterRegistry.counter(OutboxMetrics.CLEANUP_DELETED).count()).isZero();
    }
}
