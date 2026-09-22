package com.smartdelivery.order.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param clientId     this service's own identity at user-service's client-credentials
 *                     endpoint. One credential per calling service (ADR 007), so a leak
 *                     is scoped to order-service rather than to the whole platform.
 * @param clientSecret local-dev default in application.yml; MUST be overridden per
 *                     environment.
 */
@ConfigurationProperties(prefix = "services.user-service")
public record UserServiceProperties(String baseUrl, String clientId, String clientSecret) {
}
