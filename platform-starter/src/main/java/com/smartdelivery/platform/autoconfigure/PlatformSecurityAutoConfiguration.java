package com.smartdelivery.platform.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.platform.security.JwtAccessDeniedHandler;
import com.smartdelivery.platform.security.JwtAuthenticationEntryPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * The resource-server wiring that was identical in all six API services: how a token's
 * claims become authorities and a principal, and what a 401 or a 403 looks like on the
 * wire.
 *
 * <p>What is deliberately <em>not</em> here is the {@code SecurityFilterChain}. Every
 * service keeps its own, because the one thing that genuinely differs between them is
 * the only thing that matters in a filter chain: which endpoints are public, which
 * require a role, and which require ownership. Sharing a chain and parameterising it
 * with a list of path patterns would turn "who can call this" from code you read in the
 * service into configuration you assemble across two modules -- the exact trade the
 * shared/not-shared boundary in ADR 009 is drawn to avoid.
 */
@AutoConfiguration
@ConditionalOnClass({JwtAuthenticationConverter.class, ObjectMapper.class})
public class PlatformSecurityAutoConfiguration {

    /**
     * Bridges the token's claims to the two things every authorization rule in this
     * platform reads: {@code ROLE_*} authorities, and a principal name that is the user
     * id.
     *
     * <p>Spring's defaults would give neither. It looks for authorities under
     * {@code scope}/{@code scp} with a {@code SCOPE_} prefix, which no
     * {@code @PreAuthorize} in this platform asks for, and it names the principal from
     * {@code sub} only by coincidence of configuration. The principal matters as much as
     * the roles: every ownership check compares {@code authentication.getName()} against
     * a user id on a record (docs/security.md), so if that were the email, those checks
     * would start comparing two things that are never equal -- and fail closed, silently.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JwtAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAccessDeniedHandler jwtAccessDeniedHandler(ObjectMapper objectMapper) {
        return new JwtAccessDeniedHandler(objectMapper);
    }
}
