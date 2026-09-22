package com.smartdelivery.order.repository;

import com.smartdelivery.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
