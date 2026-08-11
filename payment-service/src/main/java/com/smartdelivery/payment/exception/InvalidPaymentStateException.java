package com.smartdelivery.payment.exception;

import com.smartdelivery.payment.domain.PaymentStatus;

public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(String action, PaymentStatus currentStatus) {
        super("Cannot %s a payment that is %s".formatted(action, currentStatus));
    }
}
