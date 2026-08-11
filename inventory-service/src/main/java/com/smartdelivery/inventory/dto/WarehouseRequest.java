package com.smartdelivery.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WarehouseRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String location
) {
}
