package com.smartdelivery.order.exception;

import java.util.UUID;

/**
 * Compensation for {@code orderId} did not fully succeed.
 *
 * Deliberately thrown rather than logged (which is what the pre-Phase-17 code did):
 * compensation now runs inside a {@code @KafkaListener}, so throwing is what hands the
 * failure to the retry and dead-letter machinery every other consumer in this platform
 * already uses (ADR 008). Swallowing it is the exact bug this phase fixes -- an order
 * marked CANCELLED whose stock was never released and whose payment was never refunded,
 * with nothing anywhere that would ever try again.
 *
 * Raised only after every step has been attempted, so a retry resumes with as little
 * left to do as possible; every step is idempotent, so re-attempting the ones that
 * already succeeded is harmless.
 */
public class CompensationFailedException extends RuntimeException {

    public CompensationFailedException(UUID orderId, String detail, Throwable cause) {
        super("Compensation for order %s did not complete: %s".formatted(orderId, detail), cause);
    }
}
