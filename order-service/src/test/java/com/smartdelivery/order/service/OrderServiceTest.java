package com.smartdelivery.order.service;

import com.smartdelivery.order.client.ProductServiceClient;
import com.smartdelivery.order.client.ProductSnapshot;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderItemRequest;
import com.smartdelivery.order.exception.IdempotencyKeyConflictException;
import com.smartdelivery.order.exception.InvalidOrderStateTransitionException;
import com.smartdelivery.order.exception.OrderNotFoundException;
import com.smartdelivery.order.exception.ProductNotAvailableException;
import com.smartdelivery.order.exception.ProductNotFoundException;
import com.smartdelivery.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductServiceClient productServiceClient;

    private OrderService service() {
        return new OrderService(orderRepository, productServiceClient);
    }

    private CreateOrderRequest requestFor(UUID productId, int quantity) {
        return new CreateOrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(productId, quantity)));
    }

    private Order orderWithStatus(UUID userId, OrderStatus status) {
        Order order = new Order(userId, UUID.randomUUID(), null, null);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    @Test
    void createPricesEachLineFromProductServiceAndComputesTheTotal() {
        OrderService service = service();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var request = requestFor(productId, 3);
        var snapshot = new ProductSnapshot(productId, "Widget", new BigDecimal("9.99"), true);

        when(productServiceClient.getProduct(productId)).thenReturn(Optional.of(snapshot));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order order = service.create(userId, null, request);

        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("29.97");
    }

    @Test
    void createRejectsAReferenceToAProductThatDoesNotExist() {
        OrderService service = service();
        UUID productId = UUID.randomUUID();
        when(productServiceClient.getProduct(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), null, requestFor(productId, 1)))
                .isInstanceOf(ProductNotFoundException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsAnInactiveProduct() {
        OrderService service = service();
        UUID productId = UUID.randomUUID();
        var snapshot = new ProductSnapshot(productId, "Discontinued Widget", BigDecimal.TEN, false);
        when(productServiceClient.getProduct(productId)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), null, requestFor(productId, 1)))
                .isInstanceOf(ProductNotAvailableException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void repeatedRequestWithTheSameIdempotencyKeyAndBodyReturnsTheOriginalOrderWithoutRepricing() {
        OrderService service = service();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var request = requestFor(productId, 1);
        String key = "client-key-123";
        String hash = RequestFingerprint.of(request);

        Order existing = orderWithStatus(userId, OrderStatus.CREATED);
        ReflectionTestUtils.setField(existing, "idempotencyRequestHash", hash);
        when(orderRepository.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.of(existing));

        Order result = service.create(userId, key, request);

        assertThat(result).isSameAs(existing);
        verify(productServiceClient, never()).getProduct(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void reusingAnIdempotencyKeyWithADifferentBodyIsAConflict() {
        OrderService service = service();
        UUID userId = UUID.randomUUID();
        String key = "client-key-123";

        Order existing = orderWithStatus(userId, OrderStatus.CREATED);
        ReflectionTestUtils.setField(existing, "idempotencyRequestHash", "a-completely-different-hash");
        when(orderRepository.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.of(existing));

        var newRequest = requestFor(UUID.randomUUID(), 5);

        assertThatThrownBy(() -> service.create(userId, key, newRequest))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void getByIdThrowsWhenOrderDoesNotExist() {
        OrderService service = service();
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id, UUID.randomUUID(), false)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void getByIdRejectsANonOwnerNonAdminCaller() {
        OrderService service = service();
        UUID ownerId = UUID.randomUUID();
        Order order = orderWithStatus(ownerId, OrderStatus.CREATED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getById(order.getId(), UUID.randomUUID(), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getByIdAllowsAnAdminToViewAnyOrder() {
        OrderService service = service();
        UUID ownerId = UUID.randomUUID();
        Order order = orderWithStatus(ownerId, OrderStatus.CREATED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        Order result = service.getById(order.getId(), UUID.randomUUID(), true);

        assertThat(result).isSameAs(order);
    }

    @Test
    void cancelFromCreatedSucceeds() {
        OrderService service = service();
        UUID ownerId = UUID.randomUUID();
        Order order = orderWithStatus(ownerId, OrderStatus.CREATED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        OrderCancellationResult result = service.cancel(order.getId(), ownerId, false);

        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.previousStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void cancelRejectsANonOwner() {
        OrderService service = service();
        UUID ownerId = UUID.randomUUID();
        Order order = orderWithStatus(ownerId, OrderStatus.CREATED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(order.getId(), UUID.randomUUID(), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancelRejectsAnOrderThatHasAlreadyShipped() {
        OrderService service = service();
        UUID ownerId = UUID.randomUUID();
        Order order = orderWithStatus(ownerId, OrderStatus.SHIPMENT_CREATED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(order.getId(), ownerId, false))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }
}
