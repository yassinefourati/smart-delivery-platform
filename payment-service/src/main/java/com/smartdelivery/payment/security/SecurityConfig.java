package com.smartdelivery.payment.security;

import com.smartdelivery.platform.security.JwtAccessDeniedHandler;
import com.smartdelivery.platform.security.JwtAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every payment endpoint requires ADMIN or SERVICE -- payments are not directly
 * customer-facing in this phase (there's no ownership-delegation mechanism letting
 * payment-service confirm a caller owns the order a payment belongs to without a
 * callback to order-service, and building that is out of scope here). SERVICE is the
 * service-to-service role user-service issues to order-service through the
 * client-credentials endpoint for its saga calls -- see ADR 007 and docs/security.md.
 * payment-service holds no signing key, so it can verify such a token and could never
 * mint one; before Phase 16 it could have done both.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health", "/actuator/info", "/actuator/metrics",
            // /v3/api-docs stays public: api-gateway fetches it unauthenticated to build
            // the aggregated Swagger UI (docs/api-documentation.md). The /swagger-ui
            // patterns stay listed because the UI is switched off by configuration, not
            // by removing the dependency -- turning springdoc.swagger-ui.enabled back on
            // for local debugging should not also require editing a security config.
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public SecurityConfig(
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler,
            JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/payments/**").hasAnyRole("ADMIN", "SERVICE")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                // Standard Spring Security resource server, replacing the hand-rolled
                // JwtAuthenticationFilter and JwtService deleted in Phase 16 (ADR 007):
                // tokens are now verified against user-service's published RSA public key,
                // so this service can check a token but could never mint one. The existing
                // entry point and access-denied handler are wired straight in, so the JSON
                // body of a 401 or 403 is byte-for-byte what it was before.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
