package com.smartdelivery.delivery.exception;

import java.util.UUID;

public class DeliveryAgentNotFoundException extends RuntimeException {

    public DeliveryAgentNotFoundException(UUID id) {
        super("Delivery agent not found: " + id);
    }
}
