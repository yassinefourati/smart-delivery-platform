package com.smartdelivery.delivery.web;

import com.smartdelivery.delivery.dto.AssignDeliveryRequest;
import com.smartdelivery.delivery.dto.DeliveryResponse;
import com.smartdelivery.delivery.dto.ShipmentResponse;
import com.smartdelivery.delivery.service.DeliveryService;
import com.smartdelivery.delivery.service.ShipmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Shipments are never created through this API -- {@link com.smartdelivery.delivery.event.PaymentCompletedListener}
 * creates them automatically when an order is paid (see service-boundaries.md). This
 * controller is read + dispatch (assign) only, ADMIN-only: shipment visibility and
 * dispatch decisions are back-office concerns, distinct from a delivery agent's view of
 * their own assignments (see DeliveryController).
 */
@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipments", description = "Shipment visibility and agent dispatch (ADMIN only)")
public class ShipmentController {

    private final ShipmentService shipmentService;
    private final DeliveryService deliveryService;

    public ShipmentController(ShipmentService shipmentService, DeliveryService deliveryService) {
        this.shipmentService = shipmentService;
        this.deliveryService = deliveryService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipmentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ShipmentResponse.from(shipmentService.getById(id)));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ShipmentResponse> getByOrderId(@PathVariable UUID orderId) {
        return ResponseEntity.ok(ShipmentResponse.from(shipmentService.getByOrderId(orderId)));
    }

    @GetMapping
    public ResponseEntity<List<ShipmentResponse>> list() {
        return ResponseEntity.ok(shipmentService.list().stream().map(ShipmentResponse::from).toList());
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<DeliveryResponse> assign(@PathVariable UUID id, @Valid @RequestBody AssignDeliveryRequest request) {
        var delivery = deliveryService.assign(id, request.agentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DeliveryResponse.from(delivery));
    }
}
