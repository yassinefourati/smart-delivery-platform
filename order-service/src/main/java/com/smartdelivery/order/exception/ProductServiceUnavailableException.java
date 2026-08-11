package com.smartdelivery.order.exception;

public class ProductServiceUnavailableException extends RuntimeException {

    public ProductServiceUnavailableException(Throwable cause) {
        super("Could not reach product-service to price this order; please retry", cause);
    }
}
