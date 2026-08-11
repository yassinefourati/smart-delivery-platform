package com.smartdelivery.user.dto;

import java.util.Set;
import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UUID userId,
        Set<String> roles
) {
    public LoginResponse(String accessToken, long expiresInSeconds, UUID userId, Set<String> roles) {
        this(accessToken, "Bearer", expiresInSeconds, userId, roles);
    }
}
