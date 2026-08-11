package com.smartdelivery.notification.event;

/**
 * Topic names -- see docs/kafka-events.md for the full catalog and ownership.
 * notification-service is the one service that consumes every topic in the platform
 * and publishes none of its own (see docs/service-boundaries.md) -- there is no
 * "published by notification-service" section here, unlike every other service's
 * local copy of this class.
 */
public final class KafkaTopics {

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_RELEASED = "inventory.released";
    public static final String INVENTORY_FAILED = "inventory.failed";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String SHIPMENT_CREATED = "shipment.created";
    public static final String DELIVERY_ASSIGNED = "delivery.assigned";
    public static final String DELIVERY_COMPLETED = "delivery.completed";

    private KafkaTopics() {
    }
}
