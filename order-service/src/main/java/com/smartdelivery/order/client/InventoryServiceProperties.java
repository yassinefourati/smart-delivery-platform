package com.smartdelivery.order.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.inventory-service")
public record InventoryServiceProperties(String baseUrl) {
}
