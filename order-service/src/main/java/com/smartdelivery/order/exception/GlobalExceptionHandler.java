package com.smartdelivery.order.exception;

import com.smartdelivery.platform.web.PlatformExceptionHandler;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleInvalidProduct(ProductNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT", ex.getMessage(), request);
    }

    @ExceptionHandler({ProductNotAvailableException.class, IdempotencyKeyConflictException.class,
            InvalidOrderStateTransitionException.class})
    public ResponseEntity<ProblemDetail> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockFailure(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "This order was updated concurrently; please retry", request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamUnavailable(ProductServiceUnavailableException ex, HttpServletRequest request) {
        log.warn("product-service unavailable while processing {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage(), request);
    }

    /**
     * Resilience4j's own "the call was never attempted" exceptions (open circuit, full
     * bulkhead, no rate-limit permit) -- see docs/resilience.md. Only reached on the
     * synchronous order-creation path (ProductServiceClient.getProduct); the saga's
     * calls run inside a {@code @KafkaListener} and never reach this handler at all,
     * since there's no HTTP response to build there -- they propagate to Spring Kafka's
     * retry/dead-letter handling instead (docs/saga.md#resumability).
     */
    @ExceptionHandler({CallNotPermittedException.class, BulkheadFullException.class, RequestNotPermitted.class})
    public ResponseEntity<ProblemDetail> handleResilienceRejection(Exception ex, HttpServletRequest request) {
        log.warn("Downstream call rejected by Resilience4j while processing {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "A downstream service is temporarily unavailable; please retry", request);
    }
}
