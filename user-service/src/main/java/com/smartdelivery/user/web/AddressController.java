package com.smartdelivery.user.web;

import com.smartdelivery.user.dto.AddressMapper;
import com.smartdelivery.user.dto.AddressRequest;
import com.smartdelivery.user.dto.AddressResponse;
import com.smartdelivery.user.service.AddressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{userId}/addresses")
@PreAuthorize("#userId.toString() == authentication.name or hasRole('ADMIN')")
@Tag(name = "Addresses", description = "Customer address book")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public ResponseEntity<List<AddressResponse>> list(@PathVariable UUID userId) {
        var responses = addressService.list(userId).stream().map(AddressMapper::toResponse).toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(@PathVariable UUID userId, @Valid @RequestBody AddressRequest request) {
        var address = addressService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AddressMapper.toResponse(address));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<AddressResponse> update(
            @PathVariable UUID userId, @PathVariable UUID addressId, @Valid @RequestBody AddressRequest request) {
        var address = addressService.update(userId, addressId, request);
        return ResponseEntity.ok(AddressMapper.toResponse(address));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @PathVariable UUID addressId) {
        addressService.delete(userId, addressId);
        return ResponseEntity.noContent().build();
    }
}
