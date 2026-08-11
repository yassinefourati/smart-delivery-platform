package com.smartdelivery.order.event;

import java.util.UUID;

public record PaymentFailedPayload(UUID orderId, String reason) {
}
