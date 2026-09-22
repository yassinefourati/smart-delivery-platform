package com.smartdelivery.platform.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Metadata for the OpenAPI document each service publishes at {@code /v3/api-docs},
 * which api-gateway aggregates into one Swagger UI (docs/api-documentation.md).
 *
 * @param title       the document title shown in Swagger UI. Defaults to the service's
 *                    own {@code spring.application.name}, so a service that sets nothing
 *                    is still labelled correctly in the aggregated picker.
 * @param version     the API version. Deliberately not the Maven project version: the
 *                    two answer different questions, and tying them would make every
 *                    build look like an API change.
 * @param description one line under the title.
 * @param publicUrl   the base URL clients should actually call. This is the gateway, not
 *                    the service's own host and port, because the gateway is the only
 *                    address a client is ever given -- see the class Javadoc on
 *                    {@link PlatformOpenApiConfiguration} for why this matters more than
 *                    it looks.
 */
@ConfigurationProperties(prefix = "platform.openapi")
public record PlatformOpenApiProperties(
        String title,
        @DefaultValue("v1") String version,
        String description,
        @DefaultValue("http://localhost:8080") String publicUrl) {
}
