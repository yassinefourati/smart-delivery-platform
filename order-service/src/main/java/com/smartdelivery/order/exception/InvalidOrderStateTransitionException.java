package com.smartdelivery.order.exception;

import com.smartdelivery.order.domain.OrderStatus;

public class InvalidOrderStateTransitionException extends RuntimeException {

    public InvalidOrderStateTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot move an order from %s to %s".formatted(from, to));
    }
}
