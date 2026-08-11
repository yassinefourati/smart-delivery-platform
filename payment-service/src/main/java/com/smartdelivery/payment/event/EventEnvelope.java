package com.smartdelivery.payment.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire-format contract documented in docs/kafka-events.md. Every service that
 * publishes or consumes events defines its own local copy of this envelope rather
 * than sharing a Java class across services -- see docs/architecture.md.
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
