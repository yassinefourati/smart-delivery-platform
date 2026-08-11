package com.smartdelivery.user.dto;

import java.time.Instant;
import java.util.UUID;

public record AddressResponse(
        UUID id,
        String label,
        String street,
        String city,
        String state,
        String postalCode,
        String country,
        boolean isDefault,
        Instant createdAt
) {
}
