package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.dto.WarehouseRequest;
import com.smartdelivery.inventory.exception.DuplicateWarehouseNameException;
import com.smartdelivery.inventory.exception.WarehouseNotFoundException;
import com.smartdelivery.inventory.repository.WarehouseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public WarehouseService(WarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional
    public Warehouse create(WarehouseRequest request) {
        if (warehouseRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateWarehouseNameException(request.name());
        }
        return warehouseRepository.save(new Warehouse(request.name(), request.location()));
    }

    @Transactional(readOnly = true)
    public Warehouse getById(UUID id) {
        return warehouseRepository.findById(id).orElseThrow(() -> new WarehouseNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Warehouse> list() {
        return warehouseRepository.findAll();
    }
}
