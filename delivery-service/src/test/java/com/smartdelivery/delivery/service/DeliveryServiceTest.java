package com.smartdelivery.delivery.service;

import com.smartdelivery.delivery.domain.Delivery;
import com.smartdelivery.delivery.domain.DeliveryAgent;
import com.smartdelivery.delivery.domain.Shipment;
import com.smartdelivery.delivery.event.DeliveryAssignedPayload;
import com.smartdelivery.delivery.event.DeliveryCompletedPayload;
import com.smartdelivery.delivery.event.DeliveryEventPublisher;
import com.smartdelivery.delivery.exception.DeliveryAgentNotFoundException;
import com.smartdelivery.delivery.exception.DeliveryNotFoundException;
import com.smartdelivery.delivery.exception.ShipmentAlreadyAssignedException;
import com.smartdelivery.delivery.exception.ShipmentNotFoundException;
import com.smartdelivery.delivery.repository.DeliveryAgentRepository;
import com.smartdelivery.delivery.repository.DeliveryRepository;
import com.smartdelivery.delivery.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
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
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;

    @Mock
    private DeliveryEventPublisher eventPublisher;

    private DeliveryService service() {
        return new DeliveryService(deliveryRepository, shipmentRepository, deliveryAgentRepository, eventPublisher);
    }

    private Shipment shipment(UUID orderId) {
        Shipment shipment = new Shipment(orderId);
        ReflectionTestUtils.setField(shipment, "id", UUID.randomUUID());
        return shipment;
    }

    private DeliveryAgent agent(UUID userId) {
        DeliveryAgent agent = new DeliveryAgent(userId, "Jane Doe", "+1-555-0100");
        ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
        return agent;
    }

    private Delivery delivery(UUID shipmentId, DeliveryAgent agent) {
        Delivery delivery = new Delivery(shipmentId, agent);
        ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
        return delivery;
    }

    @Test
    void assignCreatesADeliveryAndPublishesOnce() {
        Shipment shipment = shipment(UUID.randomUUID());
        DeliveryAgent agent = agent(UUID.randomUUID());
        when(shipmentRepository.findById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(deliveryAgentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(deliveryRepository.findByShipmentId(shipment.getId())).thenReturn(Optional.empty());
        when(deliveryRepository.saveAndFlush(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

        Delivery result = service().assign(shipment.getId(), agent.getId());

        assertThat(result.getAgent()).isEqualTo(agent);
        assertThat(shipment.getStatus().name()).isEqualTo("ASSIGNED");
        verify(eventPublisher).publishDeliveryAssigned(
                new DeliveryAssignedPayload(shipment.getOrderId(), shipment.getId(), agent.getId()));
    }

    @Test
    void assignIsIdempotentForARepeatedRequestToTheSameAgent() {
        Shipment shipment = shipment(UUID.randomUUID());
        DeliveryAgent agent = agent(UUID.randomUUID());
        Delivery existing = delivery(shipment.getId(), agent);
        when(shipmentRepository.findById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(deliveryAgentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(deliveryRepository.findByShipmentId(shipment.getId())).thenReturn(Optional.of(existing));

        Delivery result = service().assign(shipment.getId(), agent.getId());

        assertThat(result).isSameAs(existing);
        verify(eventPublisher, never()).publishDeliveryAssigned(any());
    }

    @Test
    void assignRejectsReassignmentToADifferentAgent() {
        Shipment shipment = shipment(UUID.randomUUID());
        DeliveryAgent firstAgent = agent(UUID.randomUUID());
        DeliveryAgent secondAgent = agent(UUID.randomUUID());
        Delivery existing = delivery(shipment.getId(), firstAgent);
        when(shipmentRepository.findById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(deliveryAgentRepository.findById(secondAgent.getId())).thenReturn(Optional.of(secondAgent));
        when(deliveryRepository.findByShipmentId(shipment.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().assign(shipment.getId(), secondAgent.getId()))
                .isInstanceOf(ShipmentAlreadyAssignedException.class);
        verify(eventPublisher, never()).publishDeliveryAssigned(any());
    }

    @Test
    void assignThrowsWhenTheShipmentDoesNotExist() {
        UUID shipmentId = UUID.randomUUID();
        when(shipmentRepository.findById(shipmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assign(shipmentId, UUID.randomUUID()))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    @Test
    void assignThrowsWhenTheAgentDoesNotExist() {
        Shipment shipment = shipment(UUID.randomUUID());
        UUID agentId = UUID.randomUUID();
        when(shipmentRepository.findById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(deliveryAgentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assign(shipment.getId(), agentId))
                .isInstanceOf(DeliveryAgentNotFoundException.class);
    }

    @Test
    void completeMarksTheDeliveryAndShipmentAndPublishes() {
        UUID userId = UUID.randomUUID();
        DeliveryAgent agent = agent(userId);
        Shipment shipment = shipment(UUID.randomUUID());
        shipment.markAssigned();
        Delivery delivery = delivery(shipment.getId(), agent);
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        when(shipmentRepository.findById(shipment.getId())).thenReturn(Optional.of(shipment));

        Delivery result = service().complete(delivery.getId(), userId, false);

        assertThat(result.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(shipment.getStatus().name()).isEqualTo("DELIVERED");
        verify(eventPublisher).publishDeliveryCompleted(
                new DeliveryCompletedPayload(shipment.getOrderId(), shipment.getId(), delivery.getDeliveredAt()));
    }

    @Test
    void completeRejectsANonOwningNonAdminCaller() {
        DeliveryAgent agent = agent(UUID.randomUUID());
        Shipment shipment = shipment(UUID.randomUUID());
        Delivery delivery = delivery(shipment.getId(), agent);
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service().complete(delivery.getId(), UUID.randomUUID(), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void completeAllowsAnAdminRegardlessOfOwnership() {
        DeliveryAgent agent = agent(UUID.randomUUID());
        Shipment shipment = shipment(UUID.randomUUID());
        shipment.markAssigned();
        Delivery delivery = delivery(shipment.getId(), agent);
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        when(shipmentRepository.findById(shipment.getId())).thenReturn(Optional.of(shipment));

        Delivery result = service().complete(delivery.getId(), UUID.randomUUID(), true);

        assertThat(result.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void completeIsIdempotentForAnAlreadyCompletedDelivery() {
        UUID userId = UUID.randomUUID();
        DeliveryAgent agent = agent(userId);
        Shipment shipment = shipment(UUID.randomUUID());
        shipment.markAssigned();
        Delivery delivery = delivery(shipment.getId(), agent);
        delivery.complete();
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        Delivery result = service().complete(delivery.getId(), userId, false);

        assertThat(result).isSameAs(delivery);
        verify(eventPublisher, never()).publishDeliveryCompleted(any());
        verify(shipmentRepository, never()).findById(any());
    }

    @Test
    void completeThrowsWhenTheDeliveryDoesNotExist() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().complete(deliveryId, UUID.randomUUID(), false))
                .isInstanceOf(DeliveryNotFoundException.class);
    }

    @Test
    void listForAgentUserReturnsEmptyWhenNoAgentIsLinkedToTheUser() {
        UUID userId = UUID.randomUUID();
        when(deliveryAgentRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(service().listForAgentUser(userId)).isEmpty();
    }
}
