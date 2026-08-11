package com.smartdelivery.inventory.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire-format contract documented in docs/kafka-events.md. Every service that
 * publishes or consumes events defines its own local copy of this envelope (same
 * fields, independently) rather than sharing a Java class across services -- see
 * docs/architecture.md on why this codebase has no shared domain module. What
 * actually has to agree between producer and consumer is the JSON shape, not a Java
 * type, which is exactly what this record captures.
 *
 * {@code payload} is a raw {@link JsonNode} rather than a generic type parameter
 * deliberately: Spring Kafka's default JSON (de)serializer support for generics
 * relies on a "__TypeId__" header naming the producer's own Java class, which does
 * not exist on a different service's classpath. Keeping the envelope's outer shape
 * fixed and the payload as a tree node sidesteps that entirely -- see
 * InventoryEventPublisher.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant timestamp,
        UUID correlationId,
        String source,
        JsonNode payload
) {
}
