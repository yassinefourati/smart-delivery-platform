package com.smartdelivery.order.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints a short-lived JWT (role {@code SERVICE}) that order-service presents to
 * inventory-service and payment-service for the saga's REST calls
 * (InventoryServiceClient / PaymentServiceClient).
 *
 * This is a deliberate, documented stand-in for a real service-to-service identity
 * system (e.g. OAuth2 client-credentials against a dedicated identity provider): it
 * works because every service already validates JWTs against the same shared HS256
 * secret (docs/security.md's "known simplification"), so signing an outgoing token
 * with that same secret is genuinely functional, not a stub -- but it means any
 * service holding the shared secret could mint one, which a real client-credentials
 * flow would not allow. Revisit alongside the HS256-to-RS256 migration noted in
 * docs/security.md.
 */
@Component
public class InternalServiceTokenProvider {

    private static final Duration TOKEN_TTL = Duration.ofSeconds(60);

    private final SecretKey signingKey;

    public InternalServiceTokenProvider(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String mintServiceToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("order-service")
                .claim("roles", List.of("SERVICE"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }
}
