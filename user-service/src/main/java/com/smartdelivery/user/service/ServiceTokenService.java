package com.smartdelivery.user.service;

import com.smartdelivery.user.dto.ServiceTokenRequest;
import com.smartdelivery.user.dto.ServiceTokenResponse;
import com.smartdelivery.user.exception.InvalidServiceClientException;
import com.smartdelivery.user.security.JwtProperties;
import com.smartdelivery.user.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The client-credentials half of authentication (ADR 007): a calling service presents
 * its own client id and secret and gets back a short-lived {@code SERVICE}-role token.
 *
 * This replaces order-service minting its own tokens, which was possible at all only
 * because every service held the same HMAC secret. The difference that matters is not
 * the shape of the call but who can do it: a compromised inventory-service can no longer
 * produce a token inventory-service will accept, because it holds no signing key and no
 * other service's client secret.
 *
 * Secrets are compared, not hashed, and that is a deliberate and bounded simplification:
 * they are deployment configuration (one env var per calling service), not user-supplied
 * passwords, so the offline-cracking risk BCrypt exists to address does not apply in the
 * same way. The comparison is still constant-time, because the timing side channel very
 * much does apply. docs/security.md records what a fuller treatment would add.
 */
@Service
public class ServiceTokenService {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenService.class);
    private static final String TOKEN_TYPE = "Bearer";

    private final JwtProperties properties;
    private final JwtService jwtService;

    public ServiceTokenService(JwtProperties properties, JwtService jwtService) {
        this.properties = properties;
        this.jwtService = jwtService;
    }

    public ServiceTokenResponse issue(ServiceTokenRequest request) {
        String configuredSecret = properties.serviceClients().get(request.clientId());
        if (configuredSecret == null || !secretsMatch(configuredSecret, request.clientSecret())) {
            // Logged at WARN with the *claimed* client id and nothing else: enough to see
            // a misconfigured deployment or a credential-stuffing attempt, never enough to
            // leak the secret that was tried.
            log.warn("Rejected service-token request for client id '{}'", request.clientId());
            throw new InvalidServiceClientException();
        }

        return new ServiceTokenResponse(
                jwtService.generateServiceToken(request.clientId()),
                TOKEN_TYPE,
                jwtService.getServiceExpirationSeconds());
    }

    private static boolean secretsMatch(String configured, String presented) {
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
