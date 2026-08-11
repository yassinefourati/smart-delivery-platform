package com.smartdelivery.payment.dto;

import com.smartdelivery.payment.domain.Payment;
import com.smartdelivery.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getCurrency(),
                payment.getStatus(), payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
