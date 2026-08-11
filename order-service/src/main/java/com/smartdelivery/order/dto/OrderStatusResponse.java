package com.smartdelivery.order.dto;

import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;

import java.util.UUID;

public record OrderStatusResponse(UUID orderId, OrderStatus status) {
    public static OrderStatusResponse from(Order order) {
        return new OrderStatusResponse(order.getId(), order.getStatus());
    }
}
