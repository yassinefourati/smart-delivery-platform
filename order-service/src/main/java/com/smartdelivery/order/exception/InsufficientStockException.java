package com.smartdelivery.order.exception;

import java.util.UUID;

/** Raised when inventory-service reports it cannot reserve the requested quantity. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(UUID productId) {
        super("Insufficient stock for product '%s'".formatted(productId));
    }
}
