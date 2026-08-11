package com.smartdelivery.delivery.exception;

import java.util.UUID;

public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException(UUID id) {
        super("Delivery not found: " + id);
    }
}
