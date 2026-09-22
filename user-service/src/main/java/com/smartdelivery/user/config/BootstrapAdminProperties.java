package com.smartdelivery.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Credentials for the optional startup ADMIN account -- see {@link BootstrapAdminInitializer}.
 * Absent by default; setting {@code email} and {@code password} is the opt-in.
 */
@ConfigurationProperties(prefix = "bootstrap.admin")
public record BootstrapAdminProperties(
        String email,
        String password,
        @DefaultValue("Bootstrap") String firstName,
        @DefaultValue("Admin") String lastName) {
}
