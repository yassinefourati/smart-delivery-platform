package com.smartdelivery.notification.event;

import java.util.UUID;

public record InventoryFailedPayload(UUID orderId, UUID productId, int quantity, String reason) {
}
