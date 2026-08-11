package com.smartdelivery.product.exception;

public class DuplicateCategoryNameException extends RuntimeException {

    public DuplicateCategoryNameException(String name) {
        super("A category named '%s' already exists".formatted(name));
    }
}
