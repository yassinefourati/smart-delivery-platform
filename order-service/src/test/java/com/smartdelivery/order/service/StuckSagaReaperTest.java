package com.smartdelivery.order.service;

import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reaper's decision-making. That its claim is actually exclusive between instances,
 * and that the lease it takes really removes an order from the eligible set, are
 * properties of the SQL rather than of this class -- see
 * {@code StuckSagaReaperIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class StuckSagaReaperTest {

    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderSagaOrchestrator orchestrator;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private StuckSagaReaper reaper() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        // Lenient because the gauge test constructs the reaper without ever polling.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new StuckSagaReaper(orderRepository, orchestrator,
                new SagaProperties(STUCK_THRESHOLD, MAX_ATTEMPTS, 50), meterRegistry, transactionManager);
    }

    private Order stuckOrder(OrderStatus status, int attemptsSoFar) {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "sagaAttempts", attemptsSoFar);
        return order;
    }

    private void claimReturns(Order... orders) {
        when(orderRepository.claimStuckSagas(any(Instant.class), anyInt())).thenReturn(List.of(orders));
    }

    @Test
    void reRunsTheSagaForAnOrderUnderTheAttemptLimit() {
        Order order = stuckOrder(OrderStatus.INVENTORY_RESERVATION_PENDING, 0);
        claimReturns(order);

        reaper().reapStuckSagas();

        verify(orchestrator).startSaga(order.getId());
        verify(orchestrator, never()).abandonSaga(any());
        assertThat(meterRegistry.counter(StuckSagaReaper.RETRIED).count()).isEqualTo(1);
    }

    /**
     * The increment happens while the claim still holds the row lock, and because
     * Hibernate writes the row it also refreshes updated_at -- which is the lease that
     * keeps a peer instance from taking the same order.
     */
    @Test
    void theClaimItselfIncrementsTheAttemptCount() {
        Order order = stuckOrder(OrderStatus.CREATED, 1);
        claimReturns(order);

        reaper().reapStuckSagas();

        assertThat(order.getSagaAttempts()).isEqualTo(2);
    }

    /** The decision is made on the count *before* this attempt, so the third retry still runs. */
    @Test
    void stillRetriesOnTheLastAttemptAllowed() {
        Order order = stuckOrder(OrderStatus.PAYMENT_PENDING, MAX_ATTEMPTS - 1);
        claimReturns(order);

        reaper().reapStuckSagas();

        verify(orchestrator).startSaga(order.getId());
        assertThat(meterRegistry.counter(StuckSagaReaper.FAILED).count()).isZero();
    }

    @Test
    void compensatesAndFailsAnOrderThatHasExhaustedItsAttempts() {
        Order order = stuckOrder(OrderStatus.INVENTORY_RESERVED, MAX_ATTEMPTS);
        claimReturns(order);

        reaper().reapStuckSagas();

        verify(orchestrator).abandonSaga(order.getId());
        verify(orchestrator, never()).startSaga(any());
        assertThat(meterRegistry.counter(StuckSagaReaper.FAILED).count()).isEqualTo(1);
    }

    /**
     * One order that cannot be resolved must not cost the rest of the batch its run --
     * and the failed one simply comes back once its lease expires, which is the retry.
     */
    @Test
    void oneUnresolvableOrderDoesNotAbandonTheRestOfTheBatch() {
        Order failing = stuckOrder(OrderStatus.CREATED, 0);
        Order healthy = stuckOrder(OrderStatus.CREATED, 0);
        claimReturns(failing, healthy);
        doThrow(new IllegalStateException("inventory-service unreachable"))
                .when(orchestrator).startSaga(failing.getId());

        reaper().reapStuckSagas();

        verify(orchestrator).startSaga(healthy.getId());
        assertThat(meterRegistry.counter(StuckSagaReaper.RETRIED).count()).isEqualTo(1);
    }

    @Test
    void claimsOrdersUntouchedForLongerThanTheStuckThreshold() {
        claimReturns();
        Instant beforeRun = Instant.now();

        reaper().reapStuckSagas();

        ArgumentCaptor<Instant> stuckSince = ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).claimStuckSagas(stuckSince.capture(), eq(50));
        assertThat(stuckSince.getValue())
                .isBetween(beforeRun.minus(STUCK_THRESHOLD), Instant.now().minus(STUCK_THRESHOLD));
    }

    @Test
    void anEmptyClaimDoesNothingAtAll() {
        claimReturns();

        reaper().reapStuckSagas();

        verify(orchestrator, never()).startSaga(any());
        verify(orchestrator, never()).abandonSaga(any());
    }

    @Test
    void theStuckGaugeReportsWhatTheReaperWouldClaim() {
        when(orderRepository.countStuckSagas(any(Instant.class))).thenReturn(4L);
        reaper();

        assertThat(meterRegistry.get(StuckSagaReaper.STUCK_COUNT).gauge().value()).isEqualTo(4.0);
    }
}
