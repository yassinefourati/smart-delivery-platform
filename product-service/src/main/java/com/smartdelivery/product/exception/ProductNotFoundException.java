package com.smartdelivery.product.exception;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID productId) {
        super("Product '%s' was not found".formatted(productId));
    }
}
