package com.smartdelivery.platform.autoconfigure;

import com.smartdelivery.platform.openapi.PlatformOpenApiConfiguration;
import com.smartdelivery.platform.openapi.PlatformOpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Gives every service the same OpenAPI document header -- title, version, the gateway as
 * the server, and the bearer scheme -- from six lines of {@code platform.openapi.*}
 * configuration instead of six copies of a {@code @Bean OpenAPI} method.
 *
 * <p>{@code @ConditionalOnMissingBean} leaves the door open: a service with a genuinely
 * unusual document (multiple servers, a second security scheme) declares its own
 * {@code OpenAPI} bean and this steps aside.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@EnableConfigurationProperties(PlatformOpenApiProperties.class)
public class PlatformOpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI platformOpenApi(PlatformOpenApiProperties properties,
                                   @Value("${spring.application.name:service}") String applicationName) {
        return PlatformOpenApiConfiguration.build(properties, applicationName);
    }
}
