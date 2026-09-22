package com.smartdelivery.order.event;

import java.util.UUID;

/**
 * @param previousStatus the status the order held immediately before it was cancelled,
 *                       which is what decides whether compensation means releasing a
 *                       reservation, refunding a payment, or doing nothing at all.
 *                       Added in Phase 17 (ADR 008) so compensation could move off the
 *                       HTTP request thread and onto this event -- by the time a
 *                       consumer reads the order back it is already CANCELLED, so
 *                       nothing else carries that information. A purely additive field:
 *                       consumers that do not know about it ignore it (see
 *                       docs/kafka-events.md on tolerant readers).
 */
public record OrderCancelledPayload(UUID orderId, UUID userId, String reason, String previousStatus) {
}
