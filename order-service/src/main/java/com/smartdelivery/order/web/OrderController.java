package com.smartdelivery.order.web;

import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderPageResponse;
import com.smartdelivery.order.dto.OrderResponse;
import com.smartdelivery.order.dto.OrderStatusResponse;
import com.smartdelivery.order.service.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order placement, tracking, and cancellation")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        UUID userId = currentUserId(authentication);
        // orderService.create() writes the OrderCreated outbox row in the same
        // transaction as the order itself (ADR 004) -- see OrderService.create.
        var order = orderService.create(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getById(Authentication authentication, @PathVariable UUID id) {
        var order = orderService.getById(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<OrderStatusResponse> getStatus(Authentication authentication, @PathVariable UUID id) {
        var order = orderService.getById(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(OrderStatusResponse.from(order));
    }

    /**
     * Returns the cancelled order, exactly as before. What changed in Phase 17 is what
     * happens after: compensation is no longer invoked from here, where a failed refund
     * or a dying pod lost it silently. OrderService.cancel writes an {@code
     * order.cancelled} outbox row in the same transaction as the cancellation, and
     * OrderCancellationListener compensates off that event -- retryable, and
     * dead-lettered if it cannot succeed. See ADR 008.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancel(Authentication authentication, @PathVariable UUID id) {
        var order = orderService.cancel(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("#userId.toString() == authentication.name or hasRole('ADMIN')")
    public ResponseEntity<OrderPageResponse> listByUser(
            @PathVariable UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        var page = orderService.listByUser(userId, pageable).map(OrderResponse::from);
        return ResponseEntity.ok(OrderPageResponse.from(page));
    }

    private UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
