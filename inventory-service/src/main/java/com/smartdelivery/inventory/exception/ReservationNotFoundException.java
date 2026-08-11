package com.smartdelivery.inventory.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID orderId, UUID productId) {
        super("No reservation found for order '%s' and product '%s'".formatted(orderId, productId));
    }
}
