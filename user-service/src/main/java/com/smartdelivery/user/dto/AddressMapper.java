package com.smartdelivery.user.dto;

import com.smartdelivery.user.domain.Address;

public final class AddressMapper {

    private AddressMapper() {
    }

    public static AddressResponse toResponse(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getLabel(),
                address.getStreet(),
                address.getCity(),
                address.getState(),
                address.getPostalCode(),
                address.getCountry(),
                address.isDefault(),
                address.getCreatedAt());
    }
}
