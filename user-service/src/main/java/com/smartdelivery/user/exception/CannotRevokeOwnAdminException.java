package com.smartdelivery.user.exception;

/**
 * An admin tried to take ADMIN away from their own account. Refused so that the last admin
 * cannot lock everyone out of administration with one click; another admin can still do it.
 */
public class CannotRevokeOwnAdminException extends RuntimeException {

    public CannotRevokeOwnAdminException() {
        super("You cannot revoke the ADMIN role from your own account");
    }
}
