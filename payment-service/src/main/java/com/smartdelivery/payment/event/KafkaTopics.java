package com.smartdelivery.payment.event;

/** Topic names -- see docs/kafka-events.md for the full catalog and ownership. */
public final class KafkaTopics {

    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";

    private KafkaTopics() {
    }
}
