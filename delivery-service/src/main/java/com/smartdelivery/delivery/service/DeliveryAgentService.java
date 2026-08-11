package com.smartdelivery.delivery.service;

import com.smartdelivery.delivery.domain.DeliveryAgent;
import com.smartdelivery.delivery.exception.DeliveryAgentNotFoundException;
import com.smartdelivery.delivery.exception.DuplicateAgentUserIdException;
import com.smartdelivery.delivery.repository.DeliveryAgentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DeliveryAgentService {

    private final DeliveryAgentRepository deliveryAgentRepository;

    public DeliveryAgentService(DeliveryAgentRepository deliveryAgentRepository) {
        this.deliveryAgentRepository = deliveryAgentRepository;
    }

    @Transactional
    public DeliveryAgent create(UUID userId, String name, String phone) {
        if (deliveryAgentRepository.existsByUserId(userId)) {
            throw new DuplicateAgentUserIdException(userId);
        }
        try {
            return deliveryAgentRepository.saveAndFlush(new DeliveryAgent(userId, name, phone));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAgentUserIdException(userId);
        }
    }

    @Transactional(readOnly = true)
    public DeliveryAgent getById(UUID id) {
        return deliveryAgentRepository.findById(id).orElseThrow(() -> new DeliveryAgentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<DeliveryAgent> list() {
        return deliveryAgentRepository.findAll();
    }
}
