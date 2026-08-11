package com.smartdelivery.inventory.event;

import java.util.UUID;

public record InventoryReleasedPayload(UUID reservationId, UUID orderId, UUID productId, int quantity) {
}
