package com.smartdelivery.delivery.service;

import com.smartdelivery.delivery.domain.Shipment;
import com.smartdelivery.delivery.event.DeliveryEventPublisher;
import com.smartdelivery.delivery.event.ShipmentCreatedPayload;
import com.smartdelivery.delivery.exception.ShipmentNotFoundException;
import com.smartdelivery.delivery.repository.ShipmentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final DeliveryEventPublisher eventPublisher;

    public ShipmentService(ShipmentRepository shipmentRepository, DeliveryEventPublisher eventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Idempotent per orderId (UNIQUE constraint on shipments.order_id): a duplicate
     * {@code payment.completed} delivery (Kafka is at-least-once, see
     * docs/kafka-events.md) finds the shipment already created and returns it instead
     * of creating a second one -- and, critically, without re-publishing
     * {@code ShipmentCreated} for an order that was already announced. The outbox write
     * happens here, inside the same transaction as the shipment itself.
     */
    @Transactional
    public Shipment createForOrder(UUID orderId) {
        var existing = shipmentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Shipment saved;
        try {
            saved = shipmentRepository.saveAndFlush(new Shipment(orderId));
        } catch (DataIntegrityViolationException e) {
            // Another concurrent payment.completed redelivery for the same order won
            // the unique-constraint race after our existence check above.
            return shipmentRepository.findByOrderId(orderId).orElseThrow(() -> e);
        }
        eventPublisher.publishShipmentCreated(new ShipmentCreatedPayload(orderId, saved.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Shipment getById(UUID id) {
        return shipmentRepository.findById(id).orElseThrow(() -> new ShipmentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Shipment getByOrderId(UUID orderId) {
        return shipmentRepository.findByOrderId(orderId).orElseThrow(() -> ShipmentNotFoundException.byOrderId(orderId));
    }

    @Transactional(readOnly = true)
    public List<Shipment> list() {
        return shipmentRepository.findAll();
    }
}
