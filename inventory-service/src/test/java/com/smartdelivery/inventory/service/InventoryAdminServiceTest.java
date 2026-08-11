package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Inventory;
import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.dto.InventoryCreateRequest;
import com.smartdelivery.inventory.exception.InventoryAlreadyExistsException;
import com.smartdelivery.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
class InventoryAdminServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private WarehouseService warehouseService;

    private InventoryAdminService service() {
        return new InventoryAdminService(inventoryRepository, warehouseService);
    }

    private Warehouse warehouseWithId() {
        Warehouse warehouse = new Warehouse("Main", "Somewhere");
        ReflectionTestUtils.setField(warehouse, "id", UUID.randomUUID());
        return warehouse;
    }

    @Test
    void createRejectsDuplicateProductWarehousePair() {
        InventoryAdminService service = service();
        UUID productId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Inventory existing = new Inventory(productId, warehouseWithId(), 5);
        when(inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId)).thenReturn(Optional.of(existing));

        var request = new InventoryCreateRequest(productId, warehouseId, 10);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(InventoryAlreadyExistsException.class);

        verify(warehouseService, never()).getById(any());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void createPersistsNewInventoryRow() {
        InventoryAdminService service = service();
        UUID productId = UUID.randomUUID();
        Warehouse warehouse = warehouseWithId();
        when(inventoryRepository.findByProductIdAndWarehouseId(productId, warehouse.getId())).thenReturn(Optional.empty());
        when(warehouseService.getById(warehouse.getId())).thenReturn(warehouse);
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new InventoryCreateRequest(productId, warehouse.getId(), 25);
        Inventory result = service.create(request);

        assertThat(result.getAvailableQuantity()).isEqualTo(25);
        assertThat(result.getProductId()).isEqualTo(productId);
    }

    @Test
    void summaryAggregatesAcrossWarehouses() {
        InventoryAdminService service = service();
        UUID productId = UUID.randomUUID();
        Inventory rowA = new Inventory(productId, warehouseWithId(), 10);
        Inventory rowB = new Inventory(productId, warehouseWithId(), 5);
        ReflectionTestUtils.setField(rowA, "reservedQuantity", 2);
        when(inventoryRepository.findByProductId(productId)).thenReturn(List.of(rowA, rowB));

        var summary = service.getSummary(productId);

        assertThat(summary.totalAvailable()).isEqualTo(15);
        assertThat(summary.totalReserved()).isEqualTo(2);
        assertThat(summary.warehouses()).hasSize(2);
    }
}
