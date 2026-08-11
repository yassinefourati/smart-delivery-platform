package com.smartdelivery.order.service;

import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSagaEventHandlerTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderSagaEventHandler handler() {
        return new OrderSagaEventHandler(orderRepository);
    }

    private Order orderWithStatus(OrderStatus status) {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    @Test
    void inventoryReservedAdvancesAnOrderWaitingOnReservation() {
        Order order = orderWithStatus(OrderStatus.INVENTORY_RESERVATION_PENDING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleInventoryReserved(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
    }

    @Test
    void duplicateInventoryReservedEventIsANoOp() {
        // Order already advanced past the state this event would apply to -- e.g. this
        // is Kafka's at-least-once redelivery of a message already processed once.
        Order order = orderWithStatus(OrderStatus.INVENTORY_RESERVED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleInventoryReserved(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
    }

    @Test
    void eventForAnOrderInAnUnexpectedStateIsSkippedNotApplied() {
        // e.g. the customer cancelled the order in the meantime.
        Order order = orderWithStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleInventoryReserved(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void eventForAnUnknownOrderIsLoggedAndSkippedWithoutThrowing() {
        UUID unknownOrderId = UUID.randomUUID();
        when(orderRepository.findById(unknownOrderId)).thenReturn(Optional.empty());

        handler().handleInventoryReserved(unknownOrderId);
        // No exception -- an unknown order is not something retrying will fix.
    }

    @Test
    void inventoryFailedMovesAnOrderWaitingOnReservationToFailed() {
        Order order = orderWithStatus(OrderStatus.INVENTORY_RESERVATION_PENDING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleInventoryFailed(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void paymentCompletedAdvancesAnOrderAwaitingPayment() {
        Order order = orderWithStatus(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handlePaymentCompleted(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void paymentFailedCancelsAnOrderAwaitingPayment() {
        Order order = orderWithStatus(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handlePaymentFailed(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void shipmentCreatedAdvancesAPaidOrder() {
        Order order = orderWithStatus(OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleShipmentCreated(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPMENT_CREATED);
    }

    @Test
    void deliveryAssignedMovesAShippedOrderOutForDelivery() {
        Order order = orderWithStatus(OrderStatus.SHIPMENT_CREATED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleDeliveryAssigned(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void deliveryCompletedMarksAnOutForDeliveryOrderDelivered() {
        Order order = orderWithStatus(OrderStatus.OUT_FOR_DELIVERY);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        handler().handleDeliveryCompleted(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }
}
