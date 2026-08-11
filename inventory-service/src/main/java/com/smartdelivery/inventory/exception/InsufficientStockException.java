package com.smartdelivery.inventory.exception;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(UUID productId, int requestedQuantity) {
        super("Not enough available stock for product '%s' to reserve %d unit(s)"
                .formatted(productId, requestedQuantity));
    }
}
