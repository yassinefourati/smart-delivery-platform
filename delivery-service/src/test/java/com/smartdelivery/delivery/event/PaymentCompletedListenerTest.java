package com.smartdelivery.delivery.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.delivery.service.ShipmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentCompletedListenerTest {

    @Mock
    private ShipmentService shipmentService;

    @Captor
    private ArgumentCaptor<UUID> orderIdCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private PaymentCompletedListener listener() {
        return new PaymentCompletedListener(objectMapper, shipmentService);
    }

    @Test
    void onPaymentCompletedCreatesAShipmentForTheOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("42.00"));
        var envelope = new EventEnvelope(
                UUID.randomUUID(), "PaymentCompleted", 1, Instant.now(), UUID.randomUUID(), "payment-service",
                objectMapper.valueToTree(payload));
        String message = objectMapper.writeValueAsString(envelope);

        listener().onPaymentCompleted(message);

        verify(shipmentService).createForOrder(orderIdCaptor.capture());
        assertThat(orderIdCaptor.getValue()).isEqualTo(orderId);
    }
}
