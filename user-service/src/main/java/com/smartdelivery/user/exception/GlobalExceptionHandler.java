package com.smartdelivery.user.exception;

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

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleEmailExists(EmailAlreadyExistsException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler({UserNotFoundException.class, AddressNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidServiceClientException.class})
    public ResponseEntity<ProblemDetail> handleUnauthorized(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), request);
    }
}
