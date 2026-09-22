package com.smartdelivery.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A client-credentials grant, in the platform's own shape rather than OAuth2's
 * {@code application/x-www-form-urlencoded} one -- every other endpoint here speaks JSON,
 * and nothing in this platform is an OAuth2 client library expecting the RFC 6749 form.
 * See ADR 007 for that scoping decision and what it would take to make it standard.
 */
public record ServiceTokenRequest(
        @NotBlank(message = "Client id is required") String clientId,
        @NotBlank(message = "Client secret is required") String clientSecret) {
}
