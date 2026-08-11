package com.smartdelivery.order.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order state machine (see docs/order-flow.md for the full diagram). Each state
 * knows only which states it may legally move to next; {@link Order#transitionTo}
 * consults this before mutating anything, so an invalid transition (e.g. shipping a
 * CANCELLED order) is rejected at the domain layer regardless of which caller --
 * today's REST controller, or Phase 7's Kafka saga listener -- attempted it.
 */
public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVATION_PENDING,
    INVENTORY_RESERVED,
    PAYMENT_PENDING,
    PAID,
    SHIPMENT_CREATED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    FAILED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(CREATED, EnumSet.of(INVENTORY_RESERVATION_PENDING, CANCELLED));
        ALLOWED_TRANSITIONS.put(INVENTORY_RESERVATION_PENDING, EnumSet.of(INVENTORY_RESERVED, FAILED, CANCELLED));
        ALLOWED_TRANSITIONS.put(INVENTORY_RESERVED, EnumSet.of(PAYMENT_PENDING, CANCELLED));
        ALLOWED_TRANSITIONS.put(PAYMENT_PENDING, EnumSet.of(PAID, CANCELLED));
        ALLOWED_TRANSITIONS.put(PAID, EnumSet.of(SHIPMENT_CREATED, CANCELLED));
        ALLOWED_TRANSITIONS.put(SHIPMENT_CREATED, EnumSet.of(OUT_FOR_DELIVERY));
        ALLOWED_TRANSITIONS.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED));
        ALLOWED_TRANSITIONS.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED_TRANSITIONS.put(FAILED, EnumSet.noneOf(OrderStatus.class));
    }

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isCancellable() {
        return canTransitionTo(CANCELLED);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
