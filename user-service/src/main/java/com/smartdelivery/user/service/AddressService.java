package com.smartdelivery.user.service;

import com.smartdelivery.user.domain.Address;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.dto.AddressRequest;
import com.smartdelivery.user.exception.AddressNotFoundException;
import com.smartdelivery.user.repository.AddressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserService userService;

    public AddressService(AddressRepository addressRepository, UserService userService) {
        this.addressRepository = addressRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<Address> list(UUID userId) {
        userService.getById(userId);
        return addressRepository.findByUserId(userId);
    }

    @Transactional
    public Address create(UUID userId, AddressRequest request) {
        User user = userService.getById(userId);
        Address address = new Address(
                user,
                request.label(),
                request.street(),
                request.city(),
                request.state(),
                request.postalCode(),
                request.country(),
                request.isDefault());
        return addressRepository.save(address);
    }

    @Transactional
    public Address update(UUID userId, UUID addressId, AddressRequest request) {
        Address address = getOwnedAddress(userId, addressId);
        address.setLabel(request.label());
        address.setStreet(request.street());
        address.setCity(request.city());
        address.setState(request.state());
        address.setPostalCode(request.postalCode());
        address.setCountry(request.country());
        address.setDefault(request.isDefault());
        return address;
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        Address address = getOwnedAddress(userId, addressId);
        addressRepository.delete(address);
    }

    private Address getOwnedAddress(UUID userId, UUID addressId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
    }
}
