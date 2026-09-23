package com.smartdelivery.payment.exception;

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

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ProblemDetail> handleConflict(InvalidPaymentStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }
}
