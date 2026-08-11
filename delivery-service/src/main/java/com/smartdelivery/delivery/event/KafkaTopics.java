package com.smartdelivery.delivery.event;

/** Topic names -- see docs/kafka-events.md for the full catalog and ownership. */
public final class KafkaTopics {

    // Consumed by delivery-service
    public static final String PAYMENT_COMPLETED = "payment.completed";

    // Published by delivery-service
    public static final String SHIPMENT_CREATED = "shipment.created";
    public static final String DELIVERY_ASSIGNED = "delivery.assigned";
    public static final String DELIVERY_COMPLETED = "delivery.completed";

    private KafkaTopics() {
    }
}
