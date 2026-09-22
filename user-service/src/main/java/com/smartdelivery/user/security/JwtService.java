package com.smartdelivery.user.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.User;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues the RS256 JWTs every other service trusts as proof of identity (ADR 007,
 * docs/security.md). This is the only class in the platform that touches a signing key:
 * a resource server holds the public half and can verify a token, but cannot mint one.
 * That asymmetry is the entire point of the phase that introduced it -- under the
 * previous shared-HMAC scheme, any service that could verify an ADMIN token could also
 * forge one.
 *
 * Every token carries a {@code kid} header naming the key that signed it, so the key can
 * be rotated without a flag day: publish the new key alongside the old in the JWKS, start
 * signing with the new one, and retire the old once nothing signed by it is still
 * unexpired.
 */
@Component
public class JwtService {

    private final JwtKeyProvider keyProvider;
    private final JwtProperties properties;
    private final RSASSASigner signer;

    public JwtService(JwtKeyProvider keyProvider, JwtProperties properties) {
        this.keyProvider = keyProvider;
        this.properties = properties;
        this.signer = new RSASSASigner(keyProvider.privateKey());
    }

    public String generateToken(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).map(Enum::name).toList();
        return sign(user.getId().toString(), roles, properties.expiration(), claims ->
                claims.claim("email", user.getEmail()));
    }

    /**
     * A client-credentials token for one calling service (see
     * {@link com.smartdelivery.user.service.ServiceTokenService}). Its subject is the
     * client id rather than a user id, which is what makes a SERVICE token
     * distinguishable in a log or a trace from a human's.
     */
    public String generateServiceToken(String clientId) {
        return sign(clientId, List.of("SERVICE"), properties.serviceExpiration(), claims -> claims);
    }

    public long getExpirationSeconds() {
        return properties.expiration().toSeconds();
    }

    public long getServiceExpirationSeconds() {
        return properties.serviceExpiration().toSeconds();
    }

    private String sign(String subject, List<String> roles, Duration ttl,
                        java.util.function.UnaryOperator<JWTClaimsSet.Builder> extraClaims) {
        Instant now = Instant.now();
        JWTClaimsSet claims = extraClaims.apply(new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issuer(properties.issuer())
                        .audience(properties.audience())
                        .claim("roles", roles)
                        .jwtID(UUID.randomUUID().toString())
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(ttl))))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyProvider.activeKid()).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            // Signing can only fail on unusable key material, which JwtKeyProvider has
            // already validated at startup -- so this is a bug here, not a bad request.
            throw new IllegalStateException("Failed to sign JWT for subject " + subject, e);
        }
        return jwt.serialize();
    }
}
