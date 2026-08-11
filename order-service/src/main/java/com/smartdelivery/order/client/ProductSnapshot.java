package com.smartdelivery.order.client;

import java.math.BigDecimal;
import java.util.UUID;

/** What order-service needs from product-service to price an order line -- see OrderService. */
public record ProductSnapshot(UUID id, String name, BigDecimal price, boolean active) {
}
