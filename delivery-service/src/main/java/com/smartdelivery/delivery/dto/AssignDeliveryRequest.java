package com.smartdelivery.delivery.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignDeliveryRequest(@NotNull UUID agentId) {
}
