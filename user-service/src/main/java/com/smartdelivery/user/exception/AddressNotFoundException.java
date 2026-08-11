package com.smartdelivery.user.exception;

import java.util.UUID;

public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(UUID addressId) {
        super("Address '%s' was not found".formatted(addressId));
    }
}
