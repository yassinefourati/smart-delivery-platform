package com.smartdelivery.inventory.security;

import com.smartdelivery.platform.security.JwtAccessDeniedHandler;
import com.smartdelivery.platform.security.JwtAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * GET /api/v1/inventory/{productId} is public (a product page can show "in stock"
 * without exposing the rest of the warehouse-management surface). Warehouse
 * management and manually stocking inventory require a human ADMIN or
 * WAREHOUSE_MANAGER. reserve/release/deduct additionally accept SERVICE: they're
 * called by order-service as part of the order Saga (docs/saga.md), carrying a
 * short-lived client-credentials token that order-service obtained from user-service by
 * presenting its own client id and secret (ADR 007). Before Phase 16 order-service signed
 * those tokens itself, which worked only because every service held the same HMAC secret
 * -- meaning this service could have minted one too. It now holds no signing key at all:
 * it can verify a SERVICE token and could not produce one.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            // The two probe paths are listed EXACTLY. requestMatchers("/actuator/health")
            // matches that path and nothing below it, so without them the kubelet's
            // unauthenticated probe got a 401 and every replica of this service would have
            // restarted forever -- while the two services with no security chain stayed up,
            // which reads as a cluster fault. Not /actuator/health/**, which would also hand
            // every component path (db, redis, circuitBreakers) to anyone. See ADR 010.
            //
            // /actuator/prometheus is a pre-existing bug fixed here: it has been exposed and
            // scraped since Phase 12 but was in no public list, so Prometheus got a 401 from
            // this service the whole time. See docs/kubernetes.md.
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/actuator/info", "/actuator/metrics", "/actuator/prometheus",
            // /v3/api-docs stays public: api-gateway fetches it unauthenticated to build
            // the aggregated Swagger UI (docs/api-documentation.md). The /swagger-ui
            // patterns stay listed because the UI is switched off by configuration, not
            // by removing the dependency -- turning springdoc.swagger-ui.enabled back on
            // for local debugging should not also require editing a security config.
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private static final String[] MANAGED_ROLES = {"ADMIN", "WAREHOUSE_MANAGER"};
    private static final String[] MANAGED_OR_SERVICE_ROLES = {"ADMIN", "WAREHOUSE_MANAGER", "SERVICE"};

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
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/*").permitAll()
                        .requestMatchers("/api/v1/inventory/reserve", "/api/v1/inventory/release", "/api/v1/inventory/deduct")
                        .hasAnyRole(MANAGED_OR_SERVICE_ROLES)
                        .requestMatchers("/api/v1/warehouses/**", "/api/v1/inventory/**").hasAnyRole(MANAGED_ROLES)
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
