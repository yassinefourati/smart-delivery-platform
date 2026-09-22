package com.smartdelivery.order.event;

/** Topic names -- see docs/kafka-events.md for the full catalog and ownership. */
public final class KafkaTopics {

    // Published by order-service
    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_CANCELLED = "order.cancelled";
    /** An order the platform gave up on, as opposed to one a customer cancelled -- see ADR 008. */
    public static final String ORDER_FAILED = "order.failed";

    // Consumed by order-service
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_FAILED = "inventory.failed";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String SHIPMENT_CREATED = "shipment.created";
    public static final String DELIVERY_ASSIGNED = "delivery.assigned";
    public static final String DELIVERY_COMPLETED = "delivery.completed";

    private KafkaTopics() {
    }
}
