package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.dto.WarehouseRequest;
import com.smartdelivery.inventory.exception.DuplicateWarehouseNameException;
import com.smartdelivery.inventory.exception.WarehouseNotFoundException;
import com.smartdelivery.inventory.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    private WarehouseService service() {
        return new WarehouseService(warehouseRepository);
    }

    @Test
    void createRejectsDuplicateNameCaseInsensitively() {
        WarehouseService service = service();
        when(warehouseRepository.existsByNameIgnoreCase("Main Warehouse")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new WarehouseRequest("Main Warehouse", "123 Dock Rd")))
                .isInstanceOf(DuplicateWarehouseNameException.class);

        verify(warehouseRepository, never()).save(any());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        WarehouseService service = service();
        UUID id = UUID.randomUUID();
        when(warehouseRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(WarehouseNotFoundException.class);
    }
}
