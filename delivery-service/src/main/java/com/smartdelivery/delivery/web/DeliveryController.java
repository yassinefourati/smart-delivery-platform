package com.smartdelivery.delivery.web;

import com.smartdelivery.delivery.dto.DeliveryResponse;
import com.smartdelivery.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A delivery agent's own view of their assignments -- ownership-enforced the same way
 * order-service enforces {@code /api/v1/orders/user/{userId}}: the role gets a
 * DELIVERY_AGENT past SecurityConfig's coarse role check, and the ownership check here
 * (agent's own {@code userId} vs. the JWT subject) decides what they can actually see
 * or touch. ADMIN bypasses ownership entirely, same as everywhere else in this platform.
 */
@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries", description = "Delivery agent's own assignments (ADMIN or the assigned DELIVERY_AGENT)")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponse> getById(Authentication authentication, @PathVariable UUID id) {
        var delivery = deliveryService.getById(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(DeliveryResponse.from(delivery));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<DeliveryResponse> complete(Authentication authentication, @PathVariable UUID id) {
        var delivery = deliveryService.complete(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(DeliveryResponse.from(delivery));
    }

    @GetMapping("/agent/{userId}")
    @PreAuthorize("#userId.toString() == authentication.name or hasRole('ADMIN')")
    public ResponseEntity<List<DeliveryResponse>> listForAgent(@PathVariable UUID userId) {
        var deliveries = deliveryService.listForAgentUser(userId).stream().map(DeliveryResponse::from).toList();
        return ResponseEntity.ok(deliveries);
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
