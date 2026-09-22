package com.smartdelivery.user.web;

import com.smartdelivery.user.dto.LoginRequest;
import com.smartdelivery.user.dto.LoginResponse;
import com.smartdelivery.user.dto.ServiceTokenRequest;
import com.smartdelivery.user.dto.ServiceTokenResponse;
import com.smartdelivery.user.service.AuthService;
import com.smartdelivery.user.service.ServiceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final ServiceTokenService serviceTokenService;

    public AuthController(AuthService authService, ServiceTokenService serviceTokenService) {
        this.authService = authService;
        this.serviceTokenService = serviceTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Client-credentials grant for one backend service calling another (ADR 007). Not a
     * customer-facing endpoint: it is reachable without a bearer token because it is
     * where a caller goes to get one, and it is authenticated by the client id and secret
     * in the body instead.
     */
    @PostMapping("/service-token")
    @Operation(summary = "Issue a service token",
            description = "Exchanges a service's client id and secret for a short-lived SERVICE-role JWT")
    public ResponseEntity<ServiceTokenResponse> serviceToken(@Valid @RequestBody ServiceTokenRequest request) {
        return ResponseEntity.ok(serviceTokenService.issue(request));
    }
}
