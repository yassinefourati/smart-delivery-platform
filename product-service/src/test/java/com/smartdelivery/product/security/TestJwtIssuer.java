package com.smartdelivery.product.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Stands in for user-service: generates RSA key material, serves it as a JWKS over a
 * real HTTP endpoint, and mints tokens against it -- including the malformed and
 * maliciously-formed ones a resource server has to refuse (ADR 007).
 *
 * The JWKS is served by an embedded {@code com.sun.net.httpserver} rather than mocked,
 * for the same reason api-gateway's routing test uses one: the behavior under test is
 * Spring Security fetching keys over the wire and selecting one by {@code kid}, and a
 * stubbed {@code JwtDecoder} would simply not exercise it. No new dependency either way.
 *
 * It deliberately holds two RSA keys. One is published; the other is a perfectly valid
 * signing key that never appears in the JWKS, which is the only honest way to test that
 * an unknown {@code kid} is rejected because the key is unknown rather than because the
 * token was malformed.
 */
public final class TestJwtIssuer implements AutoCloseable {

    public static final String ISSUER = "https://user-service.test";
    public static final String AUDIENCE = "smart-delivery-platform";
    public static final String KID = "test-key-1";
    public static final String UNKNOWN_KID = "not-in-the-jwks";

    /** The pre-Phase-16 platform-wide HMAC secret, kept only so tests can prove it is dead. */
    public static final String LEGACY_HMAC_SECRET = "local-dev-only-secret-key-do-not-use-in-production-min-32-bytes";

    private final RSAKey publishedKey;
    private final RSAKey unpublishedKey;
    private final HttpServer server;

    public TestJwtIssuer() {
        try {
            this.publishedKey = new RSAKeyGenerator(2048).keyID(KID).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
            this.unpublishedKey = new RSAKeyGenerator(2048).keyID(UNKNOWN_KID).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();

            // toString(true), not toJSONObject(...).toString(): the latter is a plain
            // java.util.Map whose toString() is Java's, not JSON.
            byte[] jwks = new JWKSet(List.of(publishedKey.toPublicJWK()))
                    .toString(true).getBytes(StandardCharsets.UTF_8);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            this.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the test JWKS endpoint", e);
        }
    }

    public String jwkSetUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** A token the platform should accept: published key, right issuer, right audience, unexpired. */
    public String token(UUID subject, String... roles) {
        return signed(publishedKey, KID, claims(subject.toString(), List.of(roles)));
    }

    public String serviceToken(String clientId) {
        return signed(publishedKey, KID, claims(clientId, List.of("SERVICE")));
    }

    /** Correctly signed by a real RSA key that this issuer simply never publishes. */
    public String tokenSignedByAnUnpublishedKey(UUID subject, String... roles) {
        return signed(unpublishedKey, UNKNOWN_KID, claims(subject.toString(), List.of(roles)));
    }

    public String expiredToken(UUID subject, String... roles) {
        return signed(publishedKey, KID, builder ->
                claims(subject.toString(), List.of(roles)).apply(builder)
                        .issueTime(Date.from(Instant.now().minus(Duration.ofHours(2))))
                        .expirationTime(Date.from(Instant.now().minus(Duration.ofHours(1)))));
    }

    public String tokenFromAnotherIssuer(UUID subject, String... roles) {
        return signed(publishedKey, KID, builder ->
                claims(subject.toString(), List.of(roles)).apply(builder).issuer("https://someone-else.test"));
    }

    public String tokenForAnotherAudience(UUID subject, String... roles) {
        return signed(publishedKey, KID, builder ->
                claims(subject.toString(), List.of(roles)).apply(builder).audience("some-other-platform"));
    }

    /** Signed with the shared HMAC secret every service used to hold before Phase 16. */
    public String legacyHmacToken(UUID subject, String... roles) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(),
                    claims(subject.toString(), List.of(roles)).apply(new JWTClaimsSet.Builder()).build());
            jwt.sign(new MACSigner(LEGACY_HMAC_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code {"alg":"none"}} with an empty signature -- the classic "just trust me" token. */
    public String unsignedToken(UUID subject, String... roles) {
        return new PlainJWT(claims(subject.toString(), List.of(roles)).apply(new JWTClaimsSet.Builder()).build())
                .serialize();
    }

    /**
     * A genuinely signed token whose payload was edited afterwards -- here, promoted to
     * ADMIN -- leaving the original signature in place.
     */
    public String tamperedToken(UUID subject) {
        String[] parts = token(subject, "CUSTOMER").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String escalated = payload.replace("\"CUSTOMER\"", "\"ADMIN\"");
        return parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(escalated.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
    }

    private static UnaryOperator<JWTClaimsSet.Builder> claims(String subject, List<String> roles) {
        Instant now = Instant.now();
        return builder -> builder
                .subject(subject)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", roles)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))));
    }

    private static String signed(RSAKey key, String kid, UnaryOperator<JWTClaimsSet.Builder> claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                    claims.apply(new JWTClaimsSet.Builder()).build());
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
