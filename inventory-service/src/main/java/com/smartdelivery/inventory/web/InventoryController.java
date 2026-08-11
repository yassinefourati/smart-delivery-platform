package com.smartdelivery.inventory.web;

import com.smartdelivery.inventory.dto.InventoryCreateRequest;
import com.smartdelivery.inventory.dto.InventoryResponse;
import com.smartdelivery.inventory.dto.InventorySummaryResponse;
import com.smartdelivery.inventory.dto.ReservationLookupRequest;
import com.smartdelivery.inventory.dto.ReservationResponse;
import com.smartdelivery.inventory.dto.ReserveRequest;
import com.smartdelivery.inventory.service.InventoryAdminService;
import com.smartdelivery.inventory.service.InventoryReservationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Warehouse stock and reservation lifecycle")
public class InventoryController {

    private final InventoryAdminService inventoryAdminService;
    private final InventoryReservationService reservationService;

    public InventoryController(InventoryAdminService inventoryAdminService, InventoryReservationService reservationService) {
        this.inventoryAdminService = inventoryAdminService;
        this.reservationService = reservationService;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<InventorySummaryResponse> getSummary(@PathVariable UUID productId) {
        return ResponseEntity.ok(inventoryAdminService.getSummary(productId));
    }

    @PostMapping
    public ResponseEntity<InventoryResponse> create(@Valid @RequestBody InventoryCreateRequest request) {
        var inventory = inventoryAdminService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(InventoryResponse.from(inventory));
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        var reservation = reservationService.reserve(request.orderId(), request.productId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    @PostMapping("/release")
    public ResponseEntity<ReservationResponse> release(@Valid @RequestBody ReservationLookupRequest request) {
        var reservation = reservationService.release(request.orderId(), request.productId());
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    @PostMapping("/deduct")
    public ResponseEntity<ReservationResponse> deduct(@Valid @RequestBody ReservationLookupRequest request) {
        var reservation = reservationService.deduct(request.orderId(), request.productId());
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }
}
