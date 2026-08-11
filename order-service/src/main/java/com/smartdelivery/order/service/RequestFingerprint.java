package com.smartdelivery.order.service;

import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderItemRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * A deterministic SHA-256 fingerprint of a create-order request's meaningful content,
 * used to detect an Idempotency-Key being replayed with the same body (fine -- see
 * OrderService) versus reused for a materially different request (a conflict, not a
 * replay).
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(CreateOrderRequest request) {
        StringBuilder canonical = new StringBuilder(request.shippingAddressId().toString()).append('|');
        request.items().stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .forEach(item -> canonical.append(item.productId()).append(':').append(item.quantity()).append(';'));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
