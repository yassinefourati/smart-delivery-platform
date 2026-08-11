package com.smartdelivery.inventory.exception;

import com.smartdelivery.inventory.domain.ReservationStatus;

public class InvalidReservationStateException extends RuntimeException {

    public InvalidReservationStateException(String action, ReservationStatus currentStatus) {
        super("Cannot %s a reservation that is already %s".formatted(action, currentStatus));
    }
}
