package com.smartdelivery.order.service;

import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderItemRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void identicalRequestsProduceTheSameFingerprint() {
        UUID addressId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var requestA = new CreateOrderRequest(addressId, List.of(new OrderItemRequest(productId, 2)));
        var requestB = new CreateOrderRequest(addressId, List.of(new OrderItemRequest(productId, 2)));

        assertThat(RequestFingerprint.of(requestA)).isEqualTo(RequestFingerprint.of(requestB));
    }

    @Test
    void itemOrderDoesNotAffectTheFingerprint() {
        UUID addressId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        var forward = new CreateOrderRequest(addressId, List.of(new OrderItemRequest(productA, 1), new OrderItemRequest(productB, 2)));
        var reversed = new CreateOrderRequest(addressId, List.of(new OrderItemRequest(productB, 2), new OrderItemRequest(productA, 1)));

        assertThat(RequestFingerprint.of(forward)).isEqualTo(RequestFingerprint.of(reversed));
    }

    @Test
    void differentQuantityProducesADifferentFingerprint() {
        UUID addressId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var requestA = new CreateOrderRequest(addressId, List.of(new OrderItemRequest(productId, 1)));
        var requestB = new CreateOrderRequest(addressId, List.of(new OrderItemRequest(productId, 2)));

        assertThat(RequestFingerprint.of(requestA)).isNotEqualTo(RequestFingerprint.of(requestB));
    }

    @Test
    void differentShippingAddressProducesADifferentFingerprint() {
        UUID productId = UUID.randomUUID();
        var requestA = new CreateOrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(productId, 1)));
        var requestB = new CreateOrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(productId, 1)));

        assertThat(RequestFingerprint.of(requestA)).isNotEqualTo(RequestFingerprint.of(requestB));
    }
}
