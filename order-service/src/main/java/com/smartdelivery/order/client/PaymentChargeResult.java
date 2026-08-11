package com.smartdelivery.order.client;

import java.util.UUID;

public record PaymentChargeResult(UUID paymentId, boolean successful) {
}
