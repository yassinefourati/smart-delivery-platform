package com.smartdelivery.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.smartdelivery.order.domain.OrderStatus.CANCELLED;
import static com.smartdelivery.order.domain.OrderStatus.CREATED;
import static com.smartdelivery.order.domain.OrderStatus.DELIVERED;
import static com.smartdelivery.order.domain.OrderStatus.FAILED;
import static com.smartdelivery.order.domain.OrderStatus.INVENTORY_RESERVATION_PENDING;
import static com.smartdelivery.order.domain.OrderStatus.INVENTORY_RESERVED;
import static com.smartdelivery.order.domain.OrderStatus.OUT_FOR_DELIVERY;
import static com.smartdelivery.order.domain.OrderStatus.PAID;
import static com.smartdelivery.order.domain.OrderStatus.PAYMENT_PENDING;
import static com.smartdelivery.order.domain.OrderStatus.SHIPMENT_CREATED;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void happyPathFollowsTheDocumentedOrderFlow() {
        assertThat(CREATED.canTransitionTo(INVENTORY_RESERVATION_PENDING)).isTrue();
        assertThat(INVENTORY_RESERVATION_PENDING.canTransitionTo(INVENTORY_RESERVED)).isTrue();
        assertThat(INVENTORY_RESERVED.canTransitionTo(PAYMENT_PENDING)).isTrue();
        assertThat(PAYMENT_PENDING.canTransitionTo(PAID)).isTrue();
        assertThat(PAID.canTransitionTo(SHIPMENT_CREATED)).isTrue();
        assertThat(SHIPMENT_CREATED.canTransitionTo(OUT_FOR_DELIVERY)).isTrue();
        assertThat(OUT_FOR_DELIVERY.canTransitionTo(DELIVERED)).isTrue();
    }

    @Test
    void cancellationIsAllowedAnyTimeBeforeShipment() {
        assertThat(CREATED.isCancellable()).isTrue();
        assertThat(INVENTORY_RESERVATION_PENDING.isCancellable()).isTrue();
        assertThat(INVENTORY_RESERVED.isCancellable()).isTrue();
        assertThat(PAYMENT_PENDING.isCancellable()).isTrue();
        assertThat(PAID.isCancellable()).isTrue();
    }

    @Test
    void cancellationIsNotAllowedOnceShipped() {
        assertThat(SHIPMENT_CREATED.isCancellable()).isFalse();
        assertThat(OUT_FOR_DELIVERY.isCancellable()).isFalse();
        assertThat(DELIVERED.isCancellable()).isFalse();
    }

    @Test
    void cannotSkipStepsForward() {
        assertThat(CREATED.canTransitionTo(PAID)).isFalse();
        assertThat(CREATED.canTransitionTo(SHIPMENT_CREATED)).isFalse();
        assertThat(INVENTORY_RESERVED.canTransitionTo(SHIPMENT_CREATED)).isFalse();
    }

    @Test
    void cannotMoveBackwards() {
        assertThat(PAID.canTransitionTo(INVENTORY_RESERVED)).isFalse();
        assertThat(SHIPMENT_CREATED.canTransitionTo(PAID)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED", "FAILED"})
    void terminalStatesHaveNoOutgoingTransitions(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (OrderStatus candidate : OrderStatus.values()) {
            assertThat(terminal.canTransitionTo(candidate)).isFalse();
        }
    }
}
