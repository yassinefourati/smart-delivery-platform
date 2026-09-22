package com.smartdelivery.order.event;

import java.util.UUID;

/**
 * An order the platform has given up on, as opposed to one a customer cancelled.
 *
 * Today this is published by {@code StuckSagaReaper} when a saga has exhausted its
 * retries (ADR 008). {@code previousStatus} is how far it had got before being
 * abandoned, which is the only thing that says what was compensated.
 */
public record OrderFailedPayload(UUID orderId, UUID userId, String reason, String previousStatus) {
}
