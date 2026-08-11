package com.smartdelivery.delivery.service;

import com.smartdelivery.delivery.domain.Shipment;
import com.smartdelivery.delivery.event.DeliveryEventPublisher;
import com.smartdelivery.delivery.event.ShipmentCreatedPayload;
import com.smartdelivery.delivery.exception.ShipmentNotFoundException;
import com.smartdelivery.delivery.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private DeliveryEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<ShipmentCreatedPayload> payloadCaptor;

    private ShipmentService service() {
        return new ShipmentService(shipmentRepository, eventPublisher);
    }

    @Test
    void createForOrderPersistsAndPublishesOnANewOrder() {
        UUID orderId = UUID.randomUUID();
        when(shipmentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(shipmentRepository.saveAndFlush(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        Shipment result = service().createForOrder(orderId);

        assertThat(result.getOrderId()).isEqualTo(orderId);
        verify(eventPublisher).publishShipmentCreated(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().orderId()).isEqualTo(orderId);
    }

    @Test
    void createForOrderIsIdempotentAndDoesNotRepublishForAnExistingShipment() {
        UUID orderId = UUID.randomUUID();
        Shipment existing = new Shipment(orderId);
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(shipmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        Shipment result = service().createForOrder(orderId);

        assertThat(result).isSameAs(existing);
        verify(eventPublisher, never()).publishShipmentCreated(any());
    }

    @Test
    void getByOrderIdThrowsWhenMissing() {
        UUID orderId = UUID.randomUUID();
        when(shipmentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getByOrderId(orderId)).isInstanceOf(ShipmentNotFoundException.class);
    }
}
