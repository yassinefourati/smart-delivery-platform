package com.smartdelivery.delivery.exception;

import java.util.UUID;

public class ShipmentNotFoundException extends RuntimeException {

    public ShipmentNotFoundException(UUID id) {
        super("Shipment not found: " + id);
    }

    public static ShipmentNotFoundException byOrderId(UUID orderId) {
        return new ShipmentNotFoundException(orderId);
    }
}
