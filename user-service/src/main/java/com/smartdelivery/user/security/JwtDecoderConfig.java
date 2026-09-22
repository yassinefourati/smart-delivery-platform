package com.smartdelivery.user.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

/**
 * user-service verifies tokens exactly as every other service does -- same RS256
 * signature, same issuer, same audience, same expiry -- but reads the public keys
 * straight out of {@link JwtKeyProvider} instead of fetching its own JWKS over HTTP.
 *
 * Pointing it at its own endpoint would work and would be one less special case, but it
 * would make user-service's ability to authenticate anyone depend on user-service being
 * reachable from itself. The keys are already in memory; the round trip has nothing to
 * discover.
 *
 * Note what this does *not* skip. The validators below are the same ones Spring Boot
 * assembles from {@code spring.security.oauth2.resourceserver.jwt.*} in every other
 * service, so a token user-service accepts is one the rest of the platform accepts too --
 * which matters, because "it worked against the issuer" is otherwise an easy way to ship
 * a token nobody else will take.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtKeyProvider keyProvider, JwtProperties properties) {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(keyProvider.publicJwkSet());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        // RS256 only: naming the algorithm here is what makes an "alg": "none" token (or
        // one re-signed with HMAC against a public key treated as a shared secret) fail
        // at key selection rather than at a claim check.
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        // Claims are Spring's validators' job below, not Nimbus's -- otherwise a failure
        // would surface as a Nimbus exception that never reaches the 401 handling.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(properties.issuer()),
                audienceValidator(properties.audience())));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                claimed -> claimed != null && claimed.contains(audience));
    }
}
