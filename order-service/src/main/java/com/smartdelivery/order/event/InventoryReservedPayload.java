package com.smartdelivery.order.event;

import java.util.UUID;

public record InventoryReservedPayload(UUID reservationId, UUID orderId, UUID productId, int quantity) {
}
