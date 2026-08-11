package com.smartdelivery.product.exception;

import java.util.UUID;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(UUID categoryId) {
        super("Category '%s' was not found".formatted(categoryId));
    }
}
