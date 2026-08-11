package com.smartdelivery.order.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.payment-service")
public record PaymentServiceProperties(String baseUrl) {
}
