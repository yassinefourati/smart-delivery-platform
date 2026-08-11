package com.smartdelivery.inventory.exception;

public class DuplicateWarehouseNameException extends RuntimeException {

    public DuplicateWarehouseNameException(String name) {
        super("A warehouse named '%s' already exists".formatted(name));
    }
}
