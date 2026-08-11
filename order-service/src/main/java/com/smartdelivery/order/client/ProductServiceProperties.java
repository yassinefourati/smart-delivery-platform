package com.smartdelivery.order.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.product-service")
public record ProductServiceProperties(String baseUrl) {
}
