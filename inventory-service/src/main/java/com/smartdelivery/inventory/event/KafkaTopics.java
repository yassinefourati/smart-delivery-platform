package com.smartdelivery.inventory.event;

/** Topic names -- see docs/kafka-events.md for the full catalog and ownership. */
public final class KafkaTopics {

    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_RELEASED = "inventory.released";
    public static final String INVENTORY_FAILED = "inventory.failed";

    private KafkaTopics() {
    }
}
