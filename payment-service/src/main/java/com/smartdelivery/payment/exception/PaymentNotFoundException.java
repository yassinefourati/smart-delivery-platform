package com.smartdelivery.payment.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    private PaymentNotFoundException(String message) {
        super(message);
    }

    public static PaymentNotFoundException byId(UUID paymentId) {
        return new PaymentNotFoundException("Payment '%s' was not found".formatted(paymentId));
    }

    public static PaymentNotFoundException byOrderId(UUID orderId) {
        return new PaymentNotFoundException("No payment found for order '%s'".formatted(orderId));
    }
}
