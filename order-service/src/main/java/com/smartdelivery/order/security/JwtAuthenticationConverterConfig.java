package com.smartdelivery.order.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Bridges the token's claims to the two things every authorization rule in this service
 * already reads: {@code ROLE_*} authorities, and a principal name that is the user id.
 *
 * Both are deliberately the same values the deleted hand-rolled
 * {@code JwtAuthenticationFilter} produced, because that is what makes this phase a
 * change of *how tokens are trusted* and not a change of who can do what. Spring's
 * defaults would have produced neither: it looks for authorities under {@code scope}/
 * {@code scp} with a {@code SCOPE_} prefix, which no {@code @PreAuthorize} here asks for.
 *
 * The principal name matters as much as the roles. Every ownership check in this
 * platform compares {@code authentication.getName()} against a user id on a record (see
 * docs/security.md); if that were the email, or the client id, those checks would stop
 * matching and quietly start denying -- or, worse, start comparing two things that are
 * never equal.
 */
@Configuration
public class JwtAuthenticationConverterConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return converter;
    }
}
