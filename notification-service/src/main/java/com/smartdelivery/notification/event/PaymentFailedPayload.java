package com.smartdelivery.notification.event;

import java.util.UUID;

public record PaymentFailedPayload(UUID orderId, String reason) {
}
