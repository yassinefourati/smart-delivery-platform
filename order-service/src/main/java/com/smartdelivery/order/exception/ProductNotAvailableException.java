package com.smartdelivery.order.exception;

import java.util.UUID;

public class ProductNotAvailableException extends RuntimeException {

    public ProductNotAvailableException(UUID productId) {
        super("Product '%s' is not currently available for order".formatted(productId));
    }
}
