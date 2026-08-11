package com.smartdelivery.user.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("An account with email '%s' already exists".formatted(email));
    }
}
