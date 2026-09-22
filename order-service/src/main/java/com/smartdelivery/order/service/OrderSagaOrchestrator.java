package com.smartdelivery.order.service;

import com.smartdelivery.order.client.InventoryServiceClient;
import com.smartdelivery.order.client.PaymentChargeResult;
import com.smartdelivery.order.client.PaymentServiceClient;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.exception.InsufficientStockException;
import com.smartdelivery.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Drives an order through Order -&gt; Inventory -&gt; Payment (docs/saga.md), triggered
 * by consuming order-service's own {@code order.created} event
 * (OrderSagaStartListener). State transitions along the way are applied through
 * {@link OrderSagaEventHandler} -- the same idempotent methods the Phase 6 Kafka
 * consumer uses for inventory.reserved/payment.completed/etc -- so there is exactly
 * one place a transition is actually decided, whether it's driven by this
 * orchestrator's synchronous REST response or by the asynchronous event
 * inventory-service/payment-service publish as a side effect of that same call. That
 * redundancy is deliberate: if this orchestrator crashes mid-saga, the async event
 * (published independently by the downstream service) still arrives later and the
 * idempotent handler advances the order anyway once the consumer resumes.
 *
 * <b>Retry-safety.</b> Every step this method takes is itself idempotent (inventory
 * reserve/release/deduct keyed by (orderId, productId); payment charge/refund keyed by
 * orderId), so if a step throws for an infrastructure reason (not caught here -- see
 * InventoryServiceClient/PaymentServiceClient), the exception propagates out of the
 * {@code @KafkaListener} and Spring Kafka retries the *entire* {@code startSaga} call.
 * A retry simply re-runs already-completed idempotent steps (they no-op) and resumes
 * from wherever it actually left off, rather than needing its own separate resume/
 * checkpoint bookkeeping.
 */
@Service
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    /** Orders in any of these states have not yet finished the reserve/charge portion of the saga. */
    private static final Set<OrderStatus> RESUMABLE_STATES = EnumSet.of(
            OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVATION_PENDING,
            OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PENDING);

    private record OrderLine(UUID productId, int quantity) {
    }

    private record OrderSnapshot(BigDecimal totalAmount, List<OrderLine> items) {
    }

    private final OrderRepository orderRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final OrderSagaEventHandler eventHandler;

    public OrderSagaOrchestrator(
            OrderRepository orderRepository,
            InventoryServiceClient inventoryServiceClient,
            PaymentServiceClient paymentServiceClient,
            OrderSagaEventHandler eventHandler) {
        this.orderRepository = orderRepository;
        this.inventoryServiceClient = inventoryServiceClient;
        this.paymentServiceClient = paymentServiceClient;
        this.eventHandler = eventHandler;
    }

    public void startSaga(UUID orderId) {
        OrderSnapshot snapshot = loadIfResumable(orderId);
        if (snapshot == null) {
            return;
        }

        eventHandler.markReservationPending(orderId);

        List<UUID> reservedProductIds = new ArrayList<>();
        for (OrderLine line : snapshot.items()) {
            try {
                inventoryServiceClient.reserve(orderId, line.productId(), line.quantity());
                reservedProductIds.add(line.productId());
            } catch (InsufficientStockException e) {
                log.warn("Insufficient stock for order {} product {}; releasing {} already-reserved item(s) and failing the order",
                        orderId, line.productId(), reservedProductIds.size());
                releaseReservations(orderId, reservedProductIds);
                eventHandler.handleInventoryFailed(orderId);
                return;
            }
        }
        eventHandler.handleInventoryReserved(orderId);

        eventHandler.markPaymentPending(orderId);

        PaymentChargeResult chargeResult = paymentServiceClient.charge(orderId, snapshot.totalAmount());
        if (!chargeResult.successful()) {
            log.warn("Payment declined for order {}; releasing {} reservation(s) and cancelling", orderId, reservedProductIds.size());
            releaseReservations(orderId, reservedProductIds);
            eventHandler.handlePaymentFailed(orderId);
            return;
        }

        eventHandler.handlePaymentCompleted(orderId);
        deductReservations(orderId, reservedProductIds);
    }

    /**
     * Compensates a customer-initiated cancellation, called by OrderController after
     * {@link OrderService#cancel} has already flipped the order to CANCELLED.
     * {@code previousStatus} decides what compensation actually means:
     * <ul>
     *   <li>CREATED: nothing was ever reserved -- no compensation needed.</li>
     *   <li>INVENTORY_RESERVATION_PENDING / INVENTORY_RESERVED / PAYMENT_PENDING:
     *       inventory is reserved but not yet paid for -- release it. (Reserving is
     *       idempotent and release on a non-existent reservation is a safe no-op on
     *       inventory-service's side, so this is safe even if the saga orchestrator
     *       hadn't actually finished reserving every line yet.)</li>
     *   <li>PAID: inventory was already permanently deducted (not just reserved) by
     *       this point -- see {@link #deductReservations} -- so there is nothing to
     *       release; refund the payment instead.</li>
     *   <li>Anything past PAID (SHIPMENT_CREATED and later): not reachable here --
     *       OrderStatus.isCancellable() already stops the order transitioning to
     *       CANCELLED that late; see docs/order-flow.md.</li>
     * </ul>
     */
    public void compensateCancellation(Order order, OrderStatus previousStatus) {
        UUID orderId = order.getId();

        if (previousStatus == OrderStatus.PAID) {
            try {
                paymentServiceClient.refund(orderId);
            } catch (Exception e) {
                log.error("Failed to refund payment for cancelled order {}", orderId, e);
            }
            return;
        }

        if (previousStatus == OrderStatus.INVENTORY_RESERVATION_PENDING
                || previousStatus == OrderStatus.INVENTORY_RESERVED
                || previousStatus == OrderStatus.PAYMENT_PENDING) {
            List<UUID> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
            releaseReservations(orderId, productIds);
        }
    }

    private void releaseReservations(UUID orderId, List<UUID> productIds) {
        for (UUID productId : productIds) {
            try {
                inventoryServiceClient.release(orderId, productId);
            } catch (Exception e) {
                // Best-effort: logged, not retried or rethrown. A stuck reservation here
                // is a known gap (needs a reconciliation job to catch it) -- not
                // something that should abort the rest of this compensation loop or
                // crash a saga that has already been correctly marked FAILED/CANCELLED.
                log.error("Failed to release reservation for order {} product {} during compensation", orderId, productId, e);
            }
        }
    }

    private void deductReservations(UUID orderId, List<UUID> productIds) {
        for (UUID productId : productIds) {
            try {
                inventoryServiceClient.deduct(orderId, productId);
            } catch (Exception e) {
                // Same best-effort reasoning as releaseReservations: the order is
                // already correctly PAID at this point, so a deduct bookkeeping
                // failure is logged for reconciliation rather than treated as a
                // reason to undo a successful payment.
                log.error("Failed to deduct reservation for order {} product {} after successful payment", orderId, productId, e);
            }
        }
    }

    /**
     * Deliberately not {@code @Transactional}: it is called from {@link #startSaga} on
     * this same bean, and a proxy-based annotation does nothing on a self-invocation --
     * writing one here would claim a guarantee that is not there (the state-transition
     * methods this class used to declare that way silently lost their writes for exactly
     * that reason; see OrderSagaEventHandler#markReservationPending). It does not need
     * one: the read runs in the repository's own transaction, and {@code items} is
     * fetched with the order by OrderRepository's entity graph, so the snapshot below is
     * complete before the order is detached.
     */
    OrderSnapshot loadIfResumable(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("order.created received for unknown order {}; ignoring", orderId);
            return null;
        }
        if (!RESUMABLE_STATES.contains(order.getStatus())) {
            log.info("order.created received for order {} which is already {}; saga already finished", orderId, order.getStatus());
            return null;
        }

        List<OrderLine> lines = order.getItems().stream()
                .map(item -> new OrderLine(item.getProductId(), item.getQuantity()))
                .toList();
        return new OrderSnapshot(order.getTotalAmount(), lines);
    }

}
