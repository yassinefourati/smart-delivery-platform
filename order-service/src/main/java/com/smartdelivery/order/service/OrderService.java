package com.smartdelivery.order.service;

import com.smartdelivery.order.client.ProductServiceClient;
import com.smartdelivery.order.client.ProductSnapshot;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderItemRequest;
import com.smartdelivery.order.event.OrderEventPublisher;
import com.smartdelivery.order.exception.IdempotencyKeyConflictException;
import com.smartdelivery.order.exception.OrderNotFoundException;
import com.smartdelivery.order.exception.ProductNotAvailableException;
import com.smartdelivery.order.exception.ProductNotFoundException;
import com.smartdelivery.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final OrderEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderRepository orderRepository, ProductServiceClient productServiceClient,
                         OrderEventPublisher eventPublisher, MeterRegistry meterRegistry,
                         PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.productServiceClient = productServiceClient;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates an order priced from live product-service data. If an Idempotency-Key is
     * supplied and already used by this user, this returns the original order instead
     * of creating a duplicate -- unless the retried request's content actually
     * differs from the first one, which is treated as a real conflict rather than
     * silently returning the wrong order (see RequestFingerprint).
     *
     * The outbox write (see OrderEventPublisher) happens inside this same transaction,
     * only on the actual-creation path -- not on a replayed idempotent hit, which would
     * otherwise re-announce an order that was already announced the first time.
     *
     * The transaction is explicit (a TransactionTemplate, not {@code @Transactional}) so
     * that the one path which must run OUTSIDE it -- recovering from a concurrent
     * duplicate, below -- can.
     */
    public Order create(UUID userId, String idempotencyKey, CreateOrderRequest request) {
        String requestHash = RequestFingerprint.of(request);
        try {
            return transactionTemplate.execute(status -> createOnce(userId, idempotencyKey, requestHash, request));
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            // Two requests with the same (userId, Idempotency-Key) both passed the existence
            // check and both inserted; the unique index made ours wait for the other one and
            // then refused it. So the winner has COMMITTED by now, and a fresh read finds it.
            //
            // The read has to happen HERE, outside the failed transaction. PostgreSQL aborts
            // a transaction on the first error, so a re-read inside it -- which is what this
            // method did before the transaction was made explicit -- fails with "current
            // transaction is aborted" and the client gets a 500 for exactly the double-submit
            // the Idempotency-Key exists to absorb. The frontend's live walk-through found it.
            Order existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> e);
            meterRegistry.counter("order.idempotency.concurrent.replays").increment();
            return reconcileReplay(existing, requestHash, idempotencyKey);
        }
    }

    /**
     * One attempt, in one transaction: the replay check, the insert and the outbox row
     * commit together or not at all. A unique-constraint loss escapes as
     * {@link DataIntegrityViolationException} after the transaction has rolled back.
     */
    private Order createOnce(UUID userId, String idempotencyKey, String requestHash, CreateOrderRequest request) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return reconcileReplay(existing.get(), requestHash, idempotencyKey);
            }
        }
        Order saved = orderRepository.saveAndFlush(buildOrder(userId, idempotencyKey, requestHash, request));
        eventPublisher.publishOrderCreated(saved);
        return saved;
    }

    private Order reconcileReplay(Order existing, String requestHash, String idempotencyKey) {
        if (!existing.getIdempotencyRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return existing;
    }

    private Order buildOrder(UUID userId, String idempotencyKey, String requestHash, CreateOrderRequest request) {
        Order order = new Order(userId, request.shippingAddressId(), idempotencyKey, requestHash);
        for (OrderItemRequest itemRequest : request.items()) {
            ProductSnapshot product = productServiceClient.getProduct(itemRequest.productId())
                    .orElseThrow(() -> new ProductNotFoundException(itemRequest.productId()));
            if (!product.active()) {
                throw new ProductNotAvailableException(itemRequest.productId());
            }
            order.addItem(new OrderItem(product.id(), product.name(), product.price(), itemRequest.quantity()));
        }
        return order;
    }

    @Transactional(readOnly = true)
    public Order getById(UUID orderId, UUID requestingUserId, boolean isAdmin) {
        Order order = fetch(orderId);
        assertOwnerOrAdmin(order, requestingUserId, isAdmin);
        return order;
    }

    @Transactional(readOnly = true)
    public Page<Order> listByUser(UUID userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable);
    }

    /**
     * Flips the order to CANCELLED if -- and only if -- it's still in a cancellable
     * state (see OrderStatus), and records the fact in the outbox inside this same
     * transaction.
     *
     * Compensation -- releasing a reservation, refunding a payment -- is deliberately
     * *not* done here, and as of Phase 17 (ADR 008) is not done by the caller either.
     * It happens when OrderCancellationListener consumes the event this method writes.
     * Before that it ran in the controller, after this transaction had already
     * committed: if the refund call failed, or the pod died in between, the order was
     * CANCELLED with its stock still held and its payment still taken, and nothing
     * anywhere would ever try again. Writing the event in the same transaction as the
     * cancellation makes "the order was cancelled" and "something will compensate for
     * it" the same atomic fact.
     *
     * The event carries {@code previousStatus} because that is what compensation
     * decides against, and by the time any consumer reads the order back it is already
     * CANCELLED.
     */
    @Transactional
    public Order cancel(UUID orderId, UUID requestingUserId, boolean isAdmin) {
        Order order = fetch(orderId);
        assertOwnerOrAdmin(order, requestingUserId, isAdmin);
        OrderStatus previousStatus = order.getStatus();
        order.cancel();
        eventPublisher.publishOrderCancelled(order, previousStatus);
        meterRegistry.counter("order.saga.outcomes", "outcome", "cancelled").increment();
        return order;
    }

    private Order fetch(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private void assertOwnerOrAdmin(Order order, UUID requestingUserId, boolean isAdmin) {
        if (!isAdmin && !order.getUserId().equals(requestingUserId)) {
            throw new AccessDeniedException("You do not have permission to access this order");
        }
    }
}
