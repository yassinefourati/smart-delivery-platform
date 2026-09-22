package com.smartdelivery.notification.event;

import java.util.UUID;

/** See order-service's OrderFailedPayload and ADR 008 -- an order the platform gave up on. */
public record OrderFailedPayload(UUID orderId, UUID userId, String reason, String previousStatus) {
}
