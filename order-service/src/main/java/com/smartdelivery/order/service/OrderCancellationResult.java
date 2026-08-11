package com.smartdelivery.order.service;

import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;

/**
 * {@code previousStatus} is what compensation (OrderSagaOrchestrator.compensateCancellation)
 * decides against -- by the time {@code order.getStatus()} is read back, it's already
 * CANCELLED, so this is the only place that information survives.
 */
public record OrderCancellationResult(Order order, OrderStatus previousStatus) {
}
