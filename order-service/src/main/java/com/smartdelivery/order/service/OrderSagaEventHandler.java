package com.smartdelivery.order.service;

import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reacts to facts other services announce over Kafka by advancing this order's own
 * state machine -- the "keep my own state in sync" half of what will become the full
 * saga once Phase 7 adds the other half (order-service actually calling
 * inventory-service/payment-service to trigger the next step). See docs/saga.md.
 *
 * Every method here is idempotent by construction, not by accident: it only applies
 * the transition if the order is still in the exact state that transition is valid
 * from. A duplicate delivery of the same event (Kafka is at-least-once, see
 * docs/kafka-events.md) finds the order already past that state and is a silent
 * no-op, not a retry-triggering error. So is an event that arrives for an order in an
 * unexpected state (e.g. the customer cancelled it in the meantime) -- reconciling
 * that kind of race is a Phase 7 concern; today it's logged and skipped rather than
 * corrupting the order's lifecycle.
 */
@Service
public class OrderSagaEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaEventHandler.class);

    private final OrderRepository orderRepository;
    private final MeterRegistry meterRegistry;

    public OrderSagaEventHandler(OrderRepository orderRepository, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void handleInventoryReserved(UUID orderId) {
        applyTransition(orderId, OrderStatus.INVENTORY_RESERVATION_PENDING, OrderStatus.INVENTORY_RESERVED, "InventoryReserved");
    }

    @Transactional
    public void handleInventoryFailed(UUID orderId) {
        applyTransition(orderId, OrderStatus.INVENTORY_RESERVATION_PENDING, OrderStatus.FAILED, "InventoryFailed");
    }

    @Transactional
    public void handlePaymentCompleted(UUID orderId) {
        applyTransition(orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, "PaymentCompleted");
    }

    @Transactional
    public void handlePaymentFailed(UUID orderId) {
        applyTransition(orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED, "PaymentFailed");
    }

    @Transactional
    public void handleShipmentCreated(UUID orderId) {
        applyTransition(orderId, OrderStatus.PAID, OrderStatus.SHIPMENT_CREATED, "ShipmentCreated");
    }

    @Transactional
    public void handleDeliveryAssigned(UUID orderId) {
        applyTransition(orderId, OrderStatus.SHIPMENT_CREATED, OrderStatus.OUT_FOR_DELIVERY, "DeliveryAssigned");
    }

    @Transactional
    public void handleDeliveryCompleted(UUID orderId) {
        applyTransition(orderId, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, "DeliveryCompleted");
    }

    private void applyTransition(UUID orderId, OrderStatus expectedFrom, OrderStatus target, String eventType) {
        var maybeOrder = orderRepository.findById(orderId);
        if (maybeOrder.isEmpty()) {
            log.warn("Received {} for unknown order {}; ignoring", eventType, orderId);
            return;
        }

        Order order = maybeOrder.get();
        if (order.getStatus() != expectedFrom) {
            log.info("Received {} for order {} which is {} (expected {}); treating as already handled",
                    eventType, orderId, order.getStatus(), expectedFrom);
            return;
        }

        order.transitionTo(target);
        recordOutcome(target);
    }

    /**
     * Feeds the {@code order.saga.outcomes} counter that backs the "order processing
     * failure rate" Grafana panel (docs/observability.md) -- one increment per order
     * that reaches a terminal status, tagged by which one. {@link OrderService#cancel}
     * increments the same counter for user-initiated cancellations; this method covers
     * every saga-driven terminal outcome.
     */
    private void recordOutcome(OrderStatus target) {
        String outcome = switch (target) {
            case DELIVERED -> "delivered";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            default -> null;
        };
        if (outcome != null) {
            meterRegistry.counter("order.saga.outcomes", "outcome", outcome).increment();
        }
    }
}
