package com.smartdelivery.order.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(idempotencyKey));
    }
}
