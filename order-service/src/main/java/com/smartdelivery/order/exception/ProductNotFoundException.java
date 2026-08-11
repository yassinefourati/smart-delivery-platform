package com.smartdelivery.order.exception;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID productId) {
        super("Product '%s' does not exist".formatted(productId));
    }
}
