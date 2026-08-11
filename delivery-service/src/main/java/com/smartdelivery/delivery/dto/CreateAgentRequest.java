package com.smartdelivery.delivery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAgentRequest(
        @NotNull UUID userId,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 30) String phone
) {
}
