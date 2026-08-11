package com.smartdelivery.payment.event;

import java.util.UUID;

public record PaymentFailedPayload(UUID orderId, String reason) {
}
