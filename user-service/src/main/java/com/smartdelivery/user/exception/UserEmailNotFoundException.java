package com.smartdelivery.user.exception;

public class UserEmailNotFoundException extends RuntimeException {

    public UserEmailNotFoundException(String email) {
        super("No user has the email '%s'".formatted(email));
    }
}
