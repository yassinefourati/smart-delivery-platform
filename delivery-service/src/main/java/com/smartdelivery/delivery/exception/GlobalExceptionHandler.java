package com.smartdelivery.delivery.exception;

import com.smartdelivery.platform.web.PlatformExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * This service's own exception mappings. The four every service shares -- validation,
 * authentication, authorization, and the catch-all -- are inherited from
 * {@link PlatformExceptionHandler} (Phase 19, ADR 009), along with the RFC 7807 response
 * body they all produce. Never leaks a stack trace to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends PlatformExceptionHandler {

    @ExceptionHandler({InvalidShipmentStateException.class, InvalidDeliveryStateException.class,
            ShipmentAlreadyAssignedException.class, DuplicateAgentUserIdException.class})
    public ResponseEntity<ProblemDetail> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler({ShipmentNotFoundException.class, DeliveryNotFoundException.class, DeliveryAgentNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }
}
