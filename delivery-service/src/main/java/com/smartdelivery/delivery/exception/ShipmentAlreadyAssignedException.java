package com.smartdelivery.delivery.exception;

import java.util.UUID;

/** Thrown when assigning a shipment that already has a Delivery for a *different* agent -- see DeliveryService.assign. */
public class ShipmentAlreadyAssignedException extends RuntimeException {

    public ShipmentAlreadyAssignedException(UUID shipmentId) {
        super("Shipment %s is already assigned to a different agent".formatted(shipmentId));
    }
}
