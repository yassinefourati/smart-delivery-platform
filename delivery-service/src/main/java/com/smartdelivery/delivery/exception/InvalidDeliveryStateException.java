package com.smartdelivery.delivery.exception;

import com.smartdelivery.delivery.domain.DeliveryStatus;

public class InvalidDeliveryStateException extends RuntimeException {

    public InvalidDeliveryStateException(String action, DeliveryStatus currentStatus) {
        super("Cannot %s a delivery in status %s".formatted(action, currentStatus));
    }
}
