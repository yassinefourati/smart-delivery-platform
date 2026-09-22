package com.smartdelivery.user.dto;

/**
 * @param expiresInSeconds how long the token is good for, so the caller can refresh
 *                         ahead of expiry rather than discovering it by getting a 401
 *                         mid-saga -- see order-service's ServiceTokenProvider.
 */
public record ServiceTokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
