package com.smartdelivery.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Everything about the tokens user-service issues (ADR 007). user-service is the only
 * service in the platform that holds a signing key at all -- every other service knows
 * nothing but the public half, fetched from this service's JWKS.
 *
 * @param issuer            the {@code iss} claim, and the value every resource server
 *                          validates against. An identifier, not necessarily a URL that
 *                          resolves.
 * @param audience          the {@code aud} claim. Present so a token minted for this
 *                          platform cannot be replayed against some other system that
 *                          happens to trust the same issuer.
 * @param expiration        how long a user token is valid for.
 * @param serviceExpiration how long a service (client-credentials) token is valid for.
 *                          Much shorter than a user token: it is fetched on demand by a
 *                          process that can always fetch another, so there is no reason
 *                          to hand out a long-lived one.
 * @param signingKey        the active key pair. Its {@code kid} goes in every token's
 *                          header so a resource server can pick the right JWKS entry.
 * @param retiredKeys       public keys of previously-active signing keys, still
 *                          published in the JWKS. This is what makes rotation possible
 *                          without invalidating every outstanding token: promote a new
 *                          key to {@code signingKey}, move the old one here, and drop it
 *                          once nothing signed by it can still be unexpired.
 * @param serviceClients    client id to shared secret, for the client-credentials
 *                          endpoint. One credential per calling service, so a leak is
 *                          scoped to that service rather than to the whole platform.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @DefaultValue("https://user-service.smart-delivery.local") String issuer,
        @DefaultValue("smart-delivery-platform") String audience,
        @DefaultValue("1h") Duration expiration,
        @DefaultValue("5m") Duration serviceExpiration,
        @DefaultValue SigningKey signingKey,
        @DefaultValue List<RetiredKey> retiredKeys,
        @DefaultValue Map<String, String> serviceClients) {

    /**
     * @param kid        key id published in the JWKS and stamped into every token header.
     * @param privateKey PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----"), from an env var or a
     *                   mounted file. Blank means "generate a throwaway key pair at
     *                   startup", which is a local-development convenience and refuses to
     *                   stay quiet about itself -- see {@link JwtKeyProvider}.
     * @param publicKey  X.509 PEM ("-----BEGIN PUBLIC KEY-----"). Optional: it is derived
     *                   from the private key when omitted, which is the normal case.
     */
    public record SigningKey(
            @DefaultValue("sdp-dev-key-1") String kid,
            @DefaultValue("") String privateKey,
            @DefaultValue("") String publicKey) {
    }

    public record RetiredKey(String kid, String publicKey) {
    }
}
