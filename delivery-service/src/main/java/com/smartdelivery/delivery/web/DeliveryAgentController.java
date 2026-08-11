package com.smartdelivery.delivery.web;

import com.smartdelivery.delivery.dto.AgentResponse;
import com.smartdelivery.delivery.dto.CreateAgentRequest;
import com.smartdelivery.delivery.service.DeliveryAgentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Delivery Agents", description = "Delivery agent management (ADMIN only)")
public class DeliveryAgentController {

    private final DeliveryAgentService deliveryAgentService;

    public DeliveryAgentController(DeliveryAgentService deliveryAgentService) {
        this.deliveryAgentService = deliveryAgentService;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> create(@Valid @RequestBody CreateAgentRequest request) {
        var agent = deliveryAgentService.create(request.userId(), request.name(), request.phone());
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentResponse.from(agent));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(AgentResponse.from(deliveryAgentService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> list() {
        return ResponseEntity.ok(deliveryAgentService.list().stream().map(AgentResponse::from).toList());
    }
}
