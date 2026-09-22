package com.smartdelivery.user.exception;

/**
 * An unknown client id or a wrong secret. Deliberately one exception for both, and with
 * a message that says neither: telling a caller which half was wrong tells an attacker
 * which client ids exist. Same reasoning as {@link InvalidCredentialsException} for
 * humans.
 */
public class InvalidServiceClientException extends RuntimeException {

    public InvalidServiceClientException() {
        super("Invalid client credentials");
    }
}
