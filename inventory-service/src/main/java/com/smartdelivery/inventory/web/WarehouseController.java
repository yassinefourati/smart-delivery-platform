package com.smartdelivery.inventory.web;

import com.smartdelivery.inventory.dto.WarehouseRequest;
import com.smartdelivery.inventory.dto.WarehouseResponse;
import com.smartdelivery.inventory.service.WarehouseService;
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

@RestController
@RequestMapping("/api/v1/warehouses")
@Tag(name = "Warehouses", description = "Warehouse management (ADMIN / WAREHOUSE_MANAGER only)")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        var warehouse = warehouseService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(WarehouseResponse.from(warehouse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WarehouseResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(WarehouseResponse.from(warehouseService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<List<WarehouseResponse>> list() {
        return ResponseEntity.ok(warehouseService.list().stream().map(WarehouseResponse::from).toList());
    }
}
