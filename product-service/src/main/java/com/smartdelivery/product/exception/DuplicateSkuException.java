package com.smartdelivery.product.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("A product with SKU '%s' already exists".formatted(sku));
    }
}
