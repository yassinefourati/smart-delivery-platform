package com.smartdelivery.product.exception;

import java.util.UUID;

public class CategoryInUseException extends RuntimeException {

    public CategoryInUseException(UUID categoryId) {
        super("Category '%s' cannot be deleted while products still reference it".formatted(categoryId));
    }
}
