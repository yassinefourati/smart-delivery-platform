package com.smartdelivery.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargeRequest(
        @NotNull UUID orderId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal amount,
        @Pattern(regexp = "[A-Z]{3}") String currency
) {
    public ChargeRequest {
        if (currency == null) {
            currency = "USD";
        }
    }
}
