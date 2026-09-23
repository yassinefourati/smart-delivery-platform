package com.smartdelivery.order.service;

import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.repository.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Finds orders whose saga stalled and either restarts it or ends it (ADR 008).
 *
 * A saga step that keeps failing is retried three times by Spring Kafka and then
 * dead-lettered (KafkaConsumerConfig). That stops one poison message wedging a partition,
 * but it leaves the order itself in whichever resumable state it reached -- CREATED,
 * INVENTORY_RESERVATION_PENDING, INVENTORY_RESERVED or PAYMENT_PENDING -- holding stock
 * that will never be released, with nothing anywhere that would ever look at it again.
 * Nothing in the platform noticed, because an order that is merely *not progressing*
 * looks exactly like one that is progressing slowly.
 *
 * <h2>Claim, then act</h2>
 * Each run claims a batch in a short transaction
 * ({@link OrderRepository#claimStuckSagas}, {@code FOR UPDATE SKIP LOCKED}) and does the
 * slow part -- REST calls into inventory- and payment-service -- after that transaction
 * has committed. The claim increments {@code saga_attempts}, which Hibernate turns into a
 * write, which refreshes {@code updated_at}: the order leaves the eligible set for another
 * {@code saga.stuck-threshold}. So the claim is also a lease, and two instances running at
 * the same moment cannot both take the same order -- one is skipped by
 * {@code SKIP LOCKED}, and by the time it polls again the order is no longer stuck.
 *
 * This is deliberately unlike {@code OutboxPublisher}, which holds its transaction across
 * its sends. There the work is one Kafka send per row and a rollback is free; here it is
 * a saga step that can take seconds and writes its own transactions as it goes, and
 * wrapping all of that in one outer transaction would turn every intermediate commit into
 * a single all-or-nothing one. A lease buys the same exclusivity without that.
 *
 * <h2>Retry, then give up</h2>
 * Under {@code saga.max-attempts}, the reaper simply re-runs
 * {@link OrderSagaOrchestrator#startSaga}, which is safe because every step of it is
 * idempotent and it resumes from whatever the order's state says actually happened. Over
 * it, {@link OrderSagaOrchestrator#abandonSaga} compensates and fails the order -- because
 * an order that cannot be completed and is never released is worse than one that is
 * honestly marked FAILED.
 */
@Component
public class StuckSagaReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckSagaReaper.class);

    static final String RETRIED = "saga.reaper.retried";
    static final String FAILED = "saga.reaper.failed";
    static final String STUCK_COUNT = "saga.stuck.count";

    /** What the reaper learned about an order while it still held the lock on it. */
    private record ClaimedSaga(UUID orderId, int attemptsBefore) {
    }

    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator orchestrator;
    private final SagaProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public StuckSagaReaper(OrderRepository orderRepository,
                           OrderSagaOrchestrator orchestrator,
                           SagaProperties properties,
                           MeterRegistry meterRegistry,
                           PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        // An explicit template rather than @Transactional on a @Scheduled method: whether
        // the scheduler ends up invoking the proxy is a matter of bean post-processor
        // ordering, and a claim whose locking clause quietly ran outside a transaction
        // would be worse than no claim at all. (order-service has already been bitten once
        // by a @Transactional that did nothing -- see OrderSagaEventHandler.)
        this.transactionTemplate = new TransactionTemplate(transactionManager);

        // Evaluated at scrape time, against the same definition of "stuck" the reaper
        // acts on. It should sit at or near zero; anything else means sagas are stalling
        // faster than the reaper resolves them, which no other signal here would show.
        Gauge.builder(STUCK_COUNT, this, StuckSagaReaper::currentlyStuck)
                .description("Orders sitting in a resumable saga state past saga.stuck-threshold")
                .register(meterRegistry);
    }

    // Initial delay defaults to 0 (reap at startup, as before). It exists so a test can park
    // the application's own reaper completely: a long interval alone still lets the first
    // run fire at startup and race the test's own rows.
    @Scheduled(fixedDelayString = "${saga.reaper-interval-ms:60000}",
            initialDelayString = "${saga.reaper-initial-delay-ms:0}")
    public void reapStuckSagas() {
        List<ClaimedSaga> claimed = claimBatch();
        for (ClaimedSaga saga : claimed) {
            resolve(saga);
        }
    }

    private List<ClaimedSaga> claimBatch() {
        Instant stuckSince = Instant.now().minus(properties.stuckThreshold());
        List<ClaimedSaga> claimed = transactionTemplate.execute(status -> {
            List<ClaimedSaga> batch = new ArrayList<>();
            for (Order order : orderRepository.claimStuckSagas(stuckSince, properties.batchSize())) {
                // recordSagaAttempt() returns the count *before* this attempt, which is
                // what the retry/give-up decision is made on, and makes the row dirty so
                // the lease (updated_at) is taken out in this same transaction.
                batch.add(new ClaimedSaga(order.getId(), order.recordSagaAttempt()));
            }
            return batch;
        });
        return claimed == null ? List.of() : claimed;
    }

    /**
     * A failure here is logged, not rethrown: the next run will find the order stuck
     * again once its lease expires, which is exactly the retry this class exists to
     * provide. Rethrowing would only abandon the rest of the claimed batch.
     */
    private void resolve(ClaimedSaga saga) {
        try {
            if (saga.attemptsBefore() < properties.maxAttempts()) {
                log.warn("Order {} has been stuck for more than {}; re-running its saga (attempt {} of {})",
                        saga.orderId(), properties.stuckThreshold(), saga.attemptsBefore() + 1, properties.maxAttempts());
                orchestrator.startSaga(saga.orderId());
                meterRegistry.counter(RETRIED).increment();
            } else {
                log.error("Order {} is still stuck after {} saga attempts; compensating and failing it",
                        saga.orderId(), saga.attemptsBefore());
                orchestrator.abandonSaga(saga.orderId());
                meterRegistry.counter(FAILED).increment();
            }
        } catch (Exception e) {
            log.error("Could not resolve stuck order {}; it will be picked up again after its lease expires",
                    saga.orderId(), e);
        }
    }

    private double currentlyStuck() {
        return orderRepository.countStuckSagas(Instant.now().minus(properties.stuckThreshold()));
    }
}
