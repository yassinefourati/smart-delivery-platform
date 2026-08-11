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
 * without exposing the rest of the warehouse-management surface). Everything else --
 * warehouse management, stocking inventory, and reserve/release/deduct -- requires
 * ADMIN or WAREHOUSE_MANAGER.
 *
 * reserve/release/deduct are meant to be called by order-service as part of the order
 * Saga (docs/saga.md), not directly by end users. The platform does not yet have a
 * dedicated service-to-service authentication mechanism (e.g. client-credentials
 * tokens) -- introducing one is a cross-cutting decision better made once more than
 * one caller needs it. Until then, these endpoints are protected the same way as
 * manual stock management; order-service will need WAREHOUSE_MANAGER-equivalent
 * credentials to call them. See docs/security.md.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health", "/actuator/info", "/actuator/metrics",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private static final String[] MANAGED_ROLES = {"ADMIN", "WAREHOUSE_MANAGER"};

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
                        .requestMatchers("/api/v1/warehouses/**", "/api/v1/inventory/**").hasAnyRole(MANAGED_ROLES)
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
