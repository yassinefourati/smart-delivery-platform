package com.smartdelivery.order.repository;

import com.smartdelivery.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read here fetches {@code items} with the order.
 *
 * {@code Order.items} is lazy, which is right for the write paths, and
 * {@code spring.jpa.open-in-view} is (correctly) false -- so an order loaded by one of
 * these methods and then mapped to an {@code OrderResponse} in the controller, outside
 * the transaction, hit a {@code LazyInitializationException} and returned a 500. The
 * entity graph makes the collection part of the same query instead of leaving it to a
 * session that has already closed.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<Order> findById(UUID id);

    /**
     * Hibernate cannot apply {@code LIMIT} in SQL alongside a collection fetch, so it
     * paginates this in memory and says so in the log (HHH90003004). Acceptable at this
     * platform's page sizes -- an order has a handful of items and a page has twenty
     * orders. If that ever stops being true, the fix is the usual two-step: page the ids,
     * then fetch those orders with their items.
     */
    @EntityGraph(attributePaths = "items")
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /**
     * Claims stuck sagas for the caller's transaction, and only for the caller's
     * transaction (ADR 008): orders sitting in a resumable state that nothing has touched
     * for longer than {@code saga.stuck-threshold}.
     *
     * {@code FOR UPDATE SKIP LOCKED} makes the claim exclusive between concurrently
     * running reaper instances, exactly as it does for the outbox poller (ADR 006). The
     * caller then bumps each claimed order's {@code updated_at} -- by incrementing
     * {@code saga_attempts}, which Hibernate turns into a write -- so the claim doubles as
     * a lease: once committed, the order is outside the eligible set until the threshold
     * passes again. That is what makes it safe to do the slow part (REST calls to
     * inventory- and payment-service) after this transaction has closed.
     *
     * Native SQL because JPQL has no {@code FOR UPDATE SKIP LOCKED}, and a literal
     * {@code LIMIT} rather than a {@link Pageable} because Spring Data appends its own
     * paging after the locking clause, which is not valid SQL.
     */
    @Query(value = """
            SELECT * FROM orders o
            WHERE o.status IN ('CREATED', 'INVENTORY_RESERVATION_PENDING', 'INVENTORY_RESERVED', 'PAYMENT_PENDING')
              AND o.updated_at < :stuckSince
            ORDER BY o.updated_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Order> claimStuckSagas(@Param("stuckSince") Instant stuckSince, @Param("batchSize") int batchSize);

    /** Backs the {@code saga.stuck.count} gauge -- see StuckSagaReaper. */
    @Query(value = """
            SELECT count(*) FROM orders o
            WHERE o.status IN ('CREATED', 'INVENTORY_RESERVATION_PENDING', 'INVENTORY_RESERVED', 'PAYMENT_PENDING')
              AND o.updated_at < :stuckSince
            """, nativeQuery = true)
    long countStuckSagas(@Param("stuckSince") Instant stuckSince);
}
