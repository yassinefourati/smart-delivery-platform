package com.smartdelivery.user.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared configuration for the issuer-side tests, so each one only states what it varies. */
final class JwtTestFixtures {

    static final String ISSUER = "https://user-service.test";
    static final String AUDIENCE = "smart-delivery-platform";
    static final String KID = "test-key-1";

    private JwtTestFixtures() {
    }

    static JwtProperties properties() {
        return properties(new JwtProperties.SigningKey(KID, "", ""));
    }

    static JwtProperties properties(JwtProperties.SigningKey signingKey) {
        return properties(signingKey, List.of());
    }

    static JwtProperties properties(JwtProperties.SigningKey signingKey, List<JwtProperties.RetiredKey> retired) {
        return new JwtProperties(ISSUER, AUDIENCE, Duration.ofHours(1), Duration.ofMinutes(5),
                signingKey, retired, Map.of("order-service", "order-service-secret"));
    }
}
