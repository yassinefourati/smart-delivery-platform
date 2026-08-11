package com.smartdelivery.delivery.service;

import com.smartdelivery.delivery.domain.Delivery;
import com.smartdelivery.delivery.domain.DeliveryAgent;
import com.smartdelivery.delivery.domain.DeliveryStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final ShipmentRepository shipmentRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final DeliveryEventPublisher eventPublisher;

    public DeliveryService(
            DeliveryRepository deliveryRepository, ShipmentRepository shipmentRepository,
            DeliveryAgentRepository deliveryAgentRepository, DeliveryEventPublisher eventPublisher) {
        this.deliveryRepository = deliveryRepository;
        this.shipmentRepository = shipmentRepository;
        this.deliveryAgentRepository = deliveryAgentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Idempotent for a repeated assignment to the *same* agent (a retried request finds
     * the Delivery already there and returns it without re-publishing). A shipment
     * already assigned to a *different* agent is a real conflict -- this phase does not
     * support reassignment, so it's rejected rather than silently overwritten. The
     * outbox write happens here, inside the same transaction as the Delivery/Shipment
     * change.
     */
    @Transactional
    public Delivery assign(UUID shipmentId, UUID agentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ShipmentNotFoundException(shipmentId));
        DeliveryAgent agent = deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new DeliveryAgentNotFoundException(agentId));

        var existing = deliveryRepository.findByShipmentId(shipmentId);
        if (existing.isPresent()) {
            if (existing.get().getAgent().getId().equals(agentId)) {
                return existing.get();
            }
            throw new ShipmentAlreadyAssignedException(shipmentId);
        }

        shipment.markAssigned();
        Delivery delivery;
        try {
            delivery = deliveryRepository.saveAndFlush(new Delivery(shipmentId, agent));
        } catch (DataIntegrityViolationException e) {
            // Another concurrent assign request for the exact same shipment won the
            // unique-constraint race after our existence check above.
            Delivery raced = deliveryRepository.findByShipmentId(shipmentId).orElseThrow(() -> e);
            if (!raced.getAgent().getId().equals(agentId)) {
                throw new ShipmentAlreadyAssignedException(shipmentId);
            }
            return raced;
        }
        eventPublisher.publishDeliveryAssigned(new DeliveryAssignedPayload(shipment.getOrderId(), shipmentId, agentId));
        return delivery;
    }

    /**
     * Idempotent no-op if the delivery is already {@code COMPLETED} -- a retried
     * completion request (network retry, or the same agent double-tapping "delivered")
     * does not re-publish {@code DeliveryCompleted} or fail. The outbox write happens
     * here, inside the same transaction as the Delivery/Shipment change.
     */
    @Transactional
    public Delivery complete(UUID deliveryId, UUID requestingUserId, boolean isAdmin) {
        Delivery delivery = fetch(deliveryId);
        assertOwnerOrAdmin(delivery, requestingUserId, isAdmin);

        if (delivery.getStatus() == DeliveryStatus.COMPLETED) {
            return delivery;
        }

        delivery.complete();
        Shipment shipment = shipmentRepository.findById(delivery.getShipmentId())
                .orElseThrow(() -> new ShipmentNotFoundException(delivery.getShipmentId()));
        shipment.markDelivered();

        eventPublisher.publishDeliveryCompleted(
                new DeliveryCompletedPayload(shipment.getOrderId(), shipment.getId(), delivery.getDeliveredAt()));
        return delivery;
    }

    @Transactional(readOnly = true)
    public Delivery getById(UUID deliveryId, UUID requestingUserId, boolean isAdmin) {
        Delivery delivery = fetch(deliveryId);
        assertOwnerOrAdmin(delivery, requestingUserId, isAdmin);
        return delivery;
    }

    @Transactional(readOnly = true)
    public List<Delivery> listForAgentUser(UUID userId) {
        return deliveryAgentRepository.findByUserId(userId)
                .map(agent -> deliveryRepository.findByAgentId(agent.getId()))
                .orElseGet(List::of);
    }

    private Delivery fetch(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId).orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
    }

    private void assertOwnerOrAdmin(Delivery delivery, UUID requestingUserId, boolean isAdmin) {
        if (!isAdmin && !delivery.getAgent().getUserId().equals(requestingUserId)) {
            throw new AccessDeniedException("You do not have permission to access this delivery");
        }
    }
}
