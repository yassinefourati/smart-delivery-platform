package com.smartdelivery.user.web;

import com.smartdelivery.user.security.JwtKeyProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the public half of user-service's signing keys, at the location RFC 7517
 * consumers look for it. Every other service in the platform points
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} here (ADR 007).
 *
 * Deliberately public and deliberately cacheable. Public because it contains nothing
 * secret -- a public key is not a credential, and a resource server has to be able to
 * fetch it before it can authenticate anything, so requiring a token would be circular.
 * Cacheable because Spring Security's {@code NimbusJwtDecoder} refetches on an unknown
 * {@code kid}, which is exactly the behavior key rotation depends on, and a short cache
 * keeps that from turning every unknown-kid probe into a round trip.
 */
@RestController
@Tag(name = "JWKS", description = "Public key set for JWT verification")
public class JwksController {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final JwtKeyProvider keyProvider;

    public JwksController(JwtKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "JSON Web Key Set", description = "Public RSA keys used to verify tokens issued by this service")
    public ResponseEntity<Map<String, Object>> jwks() {
        // toJSONObject(true) on a set built from toPublicJWK() emits public parameters
        // only; there is no code path here that could reach the private key even if the
        // provider were changed to hold one in the same set.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(keyProvider.publicJwkSet().toJSONObject(true));
    }
}
