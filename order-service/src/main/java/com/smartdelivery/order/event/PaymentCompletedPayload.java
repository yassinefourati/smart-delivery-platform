package com.smartdelivery.order.event;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCompletedPayload(UUID orderId, UUID paymentId, BigDecimal amount) {
}
