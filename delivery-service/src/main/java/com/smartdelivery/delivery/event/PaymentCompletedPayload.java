package com.smartdelivery.delivery.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Consumed only -- must match payment-service's PaymentCompletedPayload field-for-field. */
public record PaymentCompletedPayload(UUID orderId, UUID paymentId, BigDecimal amount) {
}
