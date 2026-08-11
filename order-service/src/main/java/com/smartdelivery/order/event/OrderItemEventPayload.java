package com.smartdelivery.order.event;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemEventPayload(UUID productId, String productName, BigDecimal unitPrice, int quantity) {
}
