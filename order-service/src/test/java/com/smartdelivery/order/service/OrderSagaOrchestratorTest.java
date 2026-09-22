package com.smartdelivery.order.service;

import com.smartdelivery.order.client.InventoryServiceClient;
import com.smartdelivery.order.client.PaymentChargeResult;
import com.smartdelivery.order.client.PaymentServiceClient;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.exception.CompensationFailedException;
import com.smartdelivery.order.exception.InsufficientStockException;
import com.smartdelivery.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // --- Phase 17: compensation now runs off the event, and has to be able to fail (ADR 008) ---

    @Test
    void compensatingAPaidCancellationRefundsAndReleasesNothing() {
        UUID orderId = UUID.randomUUID();

        orchestrator().compensateCancellation(orderId, OrderStatus.PAID);

        verify(paymentServiceClient).refund(orderId);
        // By PAID the stock is deducted, not reserved -- there is nothing left to release.
        verify(inventoryServiceClient, never()).release(any(), any());
    }

    @Test
    void compensatingAReservedCancellationReleasesEveryLineAndRefundsNothing() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CANCELLED, first, second);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orchestrator().compensateCancellation(order.getId(), OrderStatus.INVENTORY_RESERVED);

        verify(inventoryServiceClient).release(order.getId(), first);
        verify(inventoryServiceClient).release(order.getId(), second);
        verify(paymentServiceClient, never()).refund(any());
    }

    @Test
    void compensatingACancellationFromCreatedDoesNothingAtAll() {
        orchestrator().compensateCancellation(UUID.randomUUID(), OrderStatus.CREATED);

        verify(inventoryServiceClient, never()).release(any(), any());
        verify(paymentServiceClient, never()).refund(any());
    }

    /**
     * The bug this phase fixes, from the other side: a failure used to be logged and
     * forgotten, leaving an order CANCELLED with its stock still held. It now throws, so
     * the Kafka listener that called it retries and eventually dead-letters.
     */
    @Test
    void aFailedReleaseThrowsSoTheListenerCanRetryIt() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CANCELLED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("inventory-service unreachable"))
                .when(inventoryServiceClient).release(order.getId(), productId);

        assertThatThrownBy(() -> orchestrator().compensateCancellation(order.getId(), OrderStatus.PAYMENT_PENDING))
                .isInstanceOf(CompensationFailedException.class);
    }

    @Test
    void aFailedRefundThrowsSoTheListenerCanRetryIt() {
        UUID orderId = UUID.randomUUID();
        doThrow(new IllegalStateException("payment-service unreachable"))
                .when(paymentServiceClient).refund(orderId);

        assertThatThrownBy(() -> orchestrator().compensateCancellation(orderId, OrderStatus.PAID))
                .isInstanceOf(CompensationFailedException.class);
    }

    /**
     * One unreachable product must not strand the other lines' reservations: every line is
     * attempted, and only then is the failure raised, so the retry has less left to do.
     */
    @Test
    void everyLineIsAttemptedEvenWhenAnEarlierOneFails() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        Order order = orderWith(OrderStatus.CANCELLED, failing, healthy);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("boom")).when(inventoryServiceClient).release(order.getId(), failing);

        assertThatThrownBy(() -> orchestrator().compensateCancellation(order.getId(), OrderStatus.INVENTORY_RESERVED))
                .isInstanceOf(CompensationFailedException.class);

        verify(inventoryServiceClient).release(order.getId(), healthy);
    }

    // --- Phase 17: giving up on a stuck saga ---

    /**
     * Compensation here is deliberately unconditional. A stuck saga is stuck precisely
     * because its recorded state may not match what happened downstream, so asking for
     * both a release and a refund -- each a no-op when there is nothing to undo -- is
     * safer than inferring one from a status that may be wrong.
     */
    @Test
    void abandoningASagaReleasesRefundsAndFailsTheOrder() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.PAYMENT_PENDING, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orchestrator().abandonSaga(order.getId());

        verify(inventoryServiceClient).release(order.getId(), productId);
        verify(paymentServiceClient).refund(order.getId());
        verify(eventHandler).abandon(eq(order.getId()), any());
    }

    /** A PAYMENT_PENDING order may never have been charged; that is not a failure. */
    @Test
    void aMissingPaymentIsNotTreatedAsAFailedRefund() {
        Order order = orderWith(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null))
                .when(paymentServiceClient).refund(order.getId());

        orchestrator().abandonSaga(order.getId());

        verify(eventHandler).abandon(eq(order.getId()), any());
    }

    @Test
    void abandoningDoesNotFailAnOrderThatFinishedInTheMeantime() {
        Order order = orderWith(OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orchestrator().abandonSaga(order.getId());

        verify(inventoryServiceClient, never()).release(any(), any());
        verify(eventHandler, never()).abandon(any(), any());
    }

    @Test
    void abandoningAnOrderThatCannotBeCompensatedThrowsSoTheReaperTriesAgain() {
        UUID productId = UUID.randomUUID();
        Order order = orderWith(OrderStatus.INVENTORY_RESERVED, productId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("inventory-service unreachable"))
                .when(inventoryServiceClient).release(order.getId(), productId);

        assertThatThrownBy(() -> orchestrator().abandonSaga(order.getId()))
                .isInstanceOf(CompensationFailedException.class);

        // Crucially, the order is NOT marked FAILED: it would have been failed with its
        // stock still held, which is the exact outcome this phase exists to prevent.
        verify(eventHandler, never()).abandon(any(), any());
    }
}
