package com.smartdelivery.delivery.exception;

import com.smartdelivery.delivery.domain.ShipmentStatus;

public class InvalidShipmentStateException extends RuntimeException {

    public InvalidShipmentStateException(String action, ShipmentStatus currentStatus) {
        super("Cannot %s a shipment in status %s".formatted(action, currentStatus));
    }
}
