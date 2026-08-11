package com.smartdelivery.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RefundRequest(@NotNull UUID orderId) {
}
