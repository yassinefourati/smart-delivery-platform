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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final OrderEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, ProductServiceClient productServiceClient, OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productServiceClient = productServiceClient;
        this.eventPublisher = eventPublisher;
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
     */
    @Transactional
    public Order create(UUID userId, String idempotencyKey, CreateOrderRequest request) {
        String requestHash = RequestFingerprint.of(request);

        if (idempotencyKey != null) {
            var existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return reconcileReplay(existing.get(), requestHash, idempotencyKey);
            }
        }

        Order order = buildOrder(userId, idempotencyKey, requestHash, request);

        Order saved;
        try {
            saved = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // Another concurrent request with the exact same (userId, idempotencyKey)
            // won the unique-constraint race after our existence check above.
            if (idempotencyKey == null) {
                throw e;
            }
            Order existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElseThrow(() -> e);
            return reconcileReplay(existing, requestHash, idempotencyKey);
        }
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
     * state (see OrderStatus). Compensation (releasing a reservation, refunding a
     * payment) is a separate concern -- see OrderSagaOrchestrator.compensateCancellation,
     * called by OrderController with the {@link OrderCancellationResult#previousStatus()}
     * this method captures, since by the time an Order's status can be read back
     * it's already CANCELLED. The outbox write happens here, inside the same
     * transaction as the cancellation itself.
     */
    @Transactional
    public OrderCancellationResult cancel(UUID orderId, UUID requestingUserId, boolean isAdmin) {
        Order order = fetch(orderId);
        assertOwnerOrAdmin(order, requestingUserId, isAdmin);
        OrderStatus previousStatus = order.getStatus();
        order.cancel();
        order.getItems().size(); // force-initialize the lazy collection before the transaction (and session) closes
        eventPublisher.publishOrderCancelled(order);
        return new OrderCancellationResult(order, previousStatus);
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
