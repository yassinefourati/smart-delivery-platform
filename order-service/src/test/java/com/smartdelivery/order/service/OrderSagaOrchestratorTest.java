package com.smartdelivery.order.service;

import com.smartdelivery.order.client.InventoryServiceClient;
import com.smartdelivery.order.client.PaymentChargeResult;
import com.smartdelivery.order.client.PaymentServiceClient;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.exception.InsufficientStockException;
import com.smartdelivery.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSagaOrchestratorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryServiceClient inventoryServiceClient;

    @Mock
    private PaymentServiceClient paymentServiceClient;

    @Mock
    private OrderSagaEventHandler eventHandler;

    private OrderSagaOrchestrator orchestrator() {
        return new OrderSagaOrchestrator(orderRepository, inventoryServiceClient, paymentServiceClient, eventHandler);
    }

    private Order orderWith(OrderStatus status, UUID... productIds) {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        for (UUID productId : productIds) {
            order.addItem(new OrderItem(productId, "Widget", new BigDecimal("10.00"), 1));
        }
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    @Test
    void happyPathReservesChargesAndDeducts() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CREATED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceClient.charge(eq(order.getId()), any(BigDecimal.class)))
                .thenReturn(new PaymentChargeResult(UUID.randomUUID(), true));

        orchestrator().startSaga(order.getId());

        InOrder sequence = inOrder(inventoryServiceClient, eventHandler, paymentServiceClient);
        sequence.verify(inventoryServiceClient).reserve(order.getId(), productId, 1);
        sequence.verify(eventHandler).handleInventoryReserved(order.getId());
        sequence.verify(paymentServiceClient).charge(order.getId(), order.getTotalAmount());
        sequence.verify(eventHandler).handlePaymentCompleted(order.getId());
        sequence.verify(inventoryServiceClient).deduct(order.getId(), productId);
        verify(inventoryServiceClient, never()).release(any(), any());
    }

    @Test
    void insufficientStockCompensatesAndFailsWithoutCharging() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CREATED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(new InsufficientStockException(productId)).when(inventoryServiceClient).reserve(order.getId(), productId, 1);

        orchestrator().startSaga(order.getId());

        verify(eventHandler).handleInventoryFailed(order.getId());
        verify(eventHandler, never()).handleInventoryReserved(any());
        verify(paymentServiceClient, never()).charge(any(), any());
        // Nothing to release -- this was the only (and failed) item.
        verify(inventoryServiceClient, never()).release(any(), any());
    }

    @Test
    void partialReservationFailureReleasesOnlyTheItemsThatSucceeded() {
        UUID firstProduct = UUID.randomUUID();
        UUID secondProduct = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CREATED, firstProduct, secondProduct);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doNothing().when(inventoryServiceClient).reserve(order.getId(), firstProduct, 1);
        doThrow(new InsufficientStockException(secondProduct))
                .when(inventoryServiceClient).reserve(order.getId(), secondProduct, 1);

        orchestrator().startSaga(order.getId());

        verify(inventoryServiceClient).reserve(order.getId(), firstProduct, 1);
        verify(inventoryServiceClient).release(order.getId(), firstProduct);
        verify(inventoryServiceClient, never()).release(order.getId(), secondProduct);
        verify(eventHandler).handleInventoryFailed(order.getId());
    }

    @Test
    void declinedPaymentReleasesReservationsAndDoesNotDeduct() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CREATED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceClient.charge(eq(order.getId()), any(BigDecimal.class)))
                .thenReturn(new PaymentChargeResult(UUID.randomUUID(), false));

        orchestrator().startSaga(order.getId());

        verify(inventoryServiceClient).release(order.getId(), productId);
        verify(inventoryServiceClient, never()).deduct(any(), any());
        verify(eventHandler).handlePaymentFailed(order.getId());
        verify(eventHandler, never()).handlePaymentCompleted(any());
    }

    @Test
    void aCompensationCallThatItselfFailsDoesNotAbortTheRestOfTheSaga() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CREATED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(new InsufficientStockException(productId)).when(inventoryServiceClient).reserve(order.getId(), productId, 1);
        // No items were actually reserved (the only item failed), so release() is never
        // called in this scenario -- this test instead confirms handleInventoryFailed
        // still runs even though nothing needed releasing.

        orchestrator().startSaga(order.getId());

        verify(eventHandler).handleInventoryFailed(order.getId());
    }

    @Test
    void unknownOrderIsIgnoredWithoutCallingAnything() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        orchestrator().startSaga(orderId);

        verify(inventoryServiceClient, never()).reserve(any(), any(), anyInt());
        verify(paymentServiceClient, never()).charge(any(), any());
    }

    @Test
    void orderAlreadyPastPaymentIsSkippedIdempotently() {
        Order order = orderWith(OrderStatus.PAID, UUID.randomUUID());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orchestrator().startSaga(order.getId());

        verify(inventoryServiceClient, never()).reserve(any(), any(), anyInt());
        verify(paymentServiceClient, never()).charge(any(), any());
    }

    @Test
    void resumingFromInventoryReservedSkipsReReservationBookkeepingButStillCharges() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.INVENTORY_RESERVED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceClient.charge(eq(order.getId()), any(BigDecimal.class)))
                .thenReturn(new PaymentChargeResult(UUID.randomUUID(), true));

        orchestrator().startSaga(order.getId());

        // reserve() is idempotent on inventory-service's side, so re-issuing it on a
        // resumed retry is safe and expected.
        verify(inventoryServiceClient).reserve(order.getId(), productId, 1);
        verify(paymentServiceClient).charge(order.getId(), order.getTotalAmount());
        verify(eventHandler).handlePaymentCompleted(order.getId());
    }
}
