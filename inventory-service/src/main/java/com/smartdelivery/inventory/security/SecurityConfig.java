package com.smartdelivery.inventory.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * GET /api/v1/inventory/{productId} is public (a product page can show "in stock"
 * without exposing the rest of the warehouse-management surface). Warehouse
 * management and manually stocking inventory require a human ADMIN or
 * WAREHOUSE_MANAGER. reserve/release/deduct additionally accept SERVICE: they're
 * called by order-service as part of the order Saga (docs/saga.md), authenticating
 * with a short-lived token order-service's InternalServiceTokenProvider mints using
 * the same shared secret -- a deliberate stand-in for a real service-to-service
 * identity system (e.g. OAuth2 client-credentials against a dedicated identity
 * provider), documented as such in docs/security.md rather than pretending it's the
 * final design.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health", "/actuator/info", "/actuator/metrics",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private static final String[] MANAGED_ROLES = {"ADMIN", "WAREHOUSE_MANAGER"};
    private static final String[] MANAGED_OR_SERVICE_ROLES = {"ADMIN", "WAREHOUSE_MANAGER", "SERVICE"};

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
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
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
