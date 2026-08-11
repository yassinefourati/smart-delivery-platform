package com.smartdelivery.delivery.dto;

import com.smartdelivery.delivery.domain.DeliveryAgent;

import java.util.UUID;

public record AgentResponse(
        UUID id,
        UUID userId,
        String name,
        String phone
) {
    public static AgentResponse from(DeliveryAgent agent) {
        return new AgentResponse(agent.getId(), agent.getUserId(), agent.getName(), agent.getPhone());
    }
}
