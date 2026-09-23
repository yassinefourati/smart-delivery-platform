package com.smartdelivery.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * The four exception mappings every service in this platform had its own byte-identical
 * copy of before Phase 19 -- bean-validation failures, authentication failures,
 * authorization failures, and the catch-all -- plus the four framework-level ones that
 * used to fall through to that catch-all and come back as 500s. Each service's
 * {@code GlobalExceptionHandler} now extends this and keeps only its own domain
 * exceptions.
 *
 * <p>Deliberately <em>not</em> annotated {@code @RestControllerAdvice}. The annotation
 * stays on each service's subclass, so a service still owns the decision to have an
 * advice at all, and so this class can never be picked up as a second, competing advice
 * bean. Spring's {@code ExceptionHandlerMethodResolver} walks the whole class hierarchy
 * of an advice bean, so the inherited {@code @ExceptionHandler} methods register exactly
 * as if they had been written in the subclass.
 *
 * <p>One rule for subclasses: never re-declare a type this class already handles.
 * Two {@code @ExceptionHandler} methods mapping the same exception type is an ambiguous
 * mapping and fails the context at startup. Handling a *subtype* is fine and is the
 * intended extension point -- Spring picks the most specific match, so a service's
 * {@code handleNotFound} wins over {@link #handleUnexpected} for its own exception
 * without either having to know about the other.
 *
 * <p>What is not shared: the optimistic-locking and upstream-unavailable mappings stay
 * in the two services that have them, because their messages name the aggregate the
 * caller was working on ("this order was updated concurrently"), and a generic version
 * would be strictly less useful than what is there now.
 */
public abstract class PlatformExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> "%s: %s".formatted(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    /**
     * A request Spring could not even parse into method arguments. All four of these
     * mean "the caller sent something wrong", and all four reached {@link #handleUnexpected}
     * before Phase 19 -- so posting malformed JSON to any endpoint in this platform
     * answered <em>500 Internal Server Error</em>, logged a stack trace at ERROR, and told
     * the caller nothing about the mistake they had actually made. That was a real defect
     * and it predates this phase; it is fixed here because this is now the one place that
     * decision is made.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Deliberately not ex.getMessage(): Jackson's parse errors quote the offending
        // input, so passing it through would reflect an attacker's payload straight back.
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body is missing or is not valid JSON", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // Read once into a local: calling getRequiredType() twice is two independent
        // calls as far as the compiler is concerned, so the null check would not cover
        // the dereference (SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
        Class<?> required = ex.getRequiredType();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "%s: expected a valid %s".formatted(ex.getName(),
                        required != null ? required.getSimpleName() : "value"), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "%s is not supported by this endpoint".formatted(ex.getMethod()), request);
    }

    /**
     * No handler and no static resource for this path. Answered 500 before Phase 19, for
     * the same reason as above. Logged at debug rather than error with a stack trace: a
     * request for a path that does not exist is a client mistake or a scanner, and a
     * stack trace per 404 is how a log stops being worth reading.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNoHandler(Exception ex, HttpServletRequest request) {
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint %s %s".formatted(
                request.getMethod(), request.getRequestURI()), request);
    }

    /**
     * The database is unreachable: a 503, not a 500. Found by stopping Postgres under the
     * running services while verifying the Kubernetes probe design (Phase 20) -- every
     * database-backed request came back as a 500 INTERNAL_ERROR with a full stack trace
     * logged at ERROR.
     *
     * Both halves were wrong. A 500 tells a client "this is a bug, do not bother retrying";
     * an unreachable database is the textbook 503 -- temporary, and safe to retry, which is
     * exactly what the frontend does with SERVICE_UNAVAILABLE. And one stack trace per
     * request during an outage buries the single line that says what actually happened.
     *
     * It fails in about two seconds rather than thirty because of
     * spring.datasource.hikari.connection-timeout (docs/kubernetes.md) -- and that bound is
     * what keeps the request threads free to answer the kubelet's probes while this is
     * going on. The liveness probe stays UP throughout, so no pod is restarted over it;
     * that is ADR 010's whole point.
     *
     * CannotCreateTransactionException is what a @Transactional method throws when it
     * cannot get a connection to begin; DataAccessResourceFailureException (including
     * CannotGetJdbcConnectionException) is what a repository call outside a transaction
     * throws. Deliberately NOT the broader DataAccessException: a constraint violation or a
     * bad query is a real bug, and it should stay a 500 with its stack trace.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
        // WARN with the root cause's message and no stack trace: the cause is the useful
        // part ("Connection to localhost:5432 refused"), and it is the same on every request.
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.warn("Database unavailable while processing {} {}: {}",
                request.getMethod(), request.getRequestURI(), root.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service's database is temporarily unavailable; please retry", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleUnauthorized(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", request);
    }

    /**
     * The catch-all. Logs the exception with its stack trace and returns a body that
     * contains none of it: a stack trace tells an attacker the framework versions, the
     * package layout, and often the SQL, and tells a legitimate caller nothing they can
     * act on. The correlation id in the response is the handle for asking someone to
     * look at the log line this wrote.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    /** The one place a subclass builds its responses through, so every error has the same shape. */
    protected ResponseEntity<ProblemDetail> build(HttpStatusCode status, String error, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiErrors.of(status, error, message, request.getRequestURI()));
    }
}
