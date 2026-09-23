package com.smartdelivery.platform.web;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds the platform's single error body: an RFC 7807 {@link ProblemDetail} that still
 * carries every field the pre-Phase-19 {@code ErrorResponse} record had.
 *
 * <p>The migration is deliberately additive. RFC 7807's own members are populated
 * ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}), and
 * the five fields clients have been reading since Phase 2 -- {@code timestamp},
 * {@code error}, {@code message}, {@code path}, {@code correlationId} -- are written
 * back as extension properties alongside them, with the same names and the same values.
 * {@code status} is an RFC 7807 member *and* an original field, and happens to mean the
 * same thing in both, so it needs no duplicate. The result is a strict superset of the
 * old body: anything that parsed the old shape still parses this one.
 *
 * <p>That redundancy is the point. Renaming {@code message} to {@code detail} would be
 * the tidier RFC-native shape and would break every client that reads {@code message},
 * which includes this platform's own smoke test and, more importantly, whatever a real
 * consumer has already written. The duplication costs a few bytes per error response
 * and buys a migration nobody has to coordinate; it is the same "add fields, never
 * rename or remove" rule the event payloads follow (see docs/kafka-events.md). If the
 * extension properties are ever dropped, that is a breaking change and needs announcing
 * as one.
 *
 * <p>The one thing that does change on the wire is the media type: a {@code ProblemDetail}
 * is serialized as {@code application/problem+json} rather than {@code application/json}.
 * That is what makes the response self-describing, it is still JSON to any parser, and
 * it is the reason to adopt RFC 7807 in the first place.
 */
public final class ApiErrors {

    /**
     * Base URI for the {@code type} member. It is deliberately a documentation URL and
     * not a resolvable endpoint on this platform: RFC 7807 asks for a stable identifier
     * that a human can follow, and inventing a {@code /errors/{code}} API that nothing
     * serves would be worse than pointing at the document that actually explains the
     * codes.
     */
    public static final String TYPE_BASE = "https://github.com/yassinefourati/smart-delivery-platform/blob/main/docs/security.md#error-codes";

    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private ApiErrors() {
    }

    /**
     * @param status the HTTP status to return
     * @param error  the platform's own error code, e.g. {@code VALIDATION_ERROR}. Kept as
     *               the {@code error} extension property and mirrored into RFC 7807's
     *               {@code title}, since it is exactly what that member is for: a short,
     *               human-readable summary that does not change from occurrence to
     *               occurrence.
     * @param message the human-readable explanation, mirrored into {@code detail}
     * @param path    the request URI, mirrored into {@code instance}
     */
    public static ProblemDetail of(HttpStatusCode status, String error, String message, String path) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE));
        problem.setTitle(error);
        problem.setDetail(message);
        if (path != null) {
            // setInstance takes a URI; a request URI is a path, which is a valid
            // relative URI reference, so this never needs escaping beyond what the
            // servlet container already did.
            problem.setInstance(URI.create(path));
            problem.setProperty("path", path);
        }
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("error", error);
        problem.setProperty("message", message);
        problem.setProperty("correlationId", correlationId());
        return problem;
    }

    public static ProblemDetail of(HttpStatus status, String error, String message, String path) {
        return of((HttpStatusCode) status, error, message, path);
    }

    /**
     * The correlation id for the request being answered, falling back to a fresh one.
     *
     * <p>Before Phase 19 the fallback was the *only* behaviour in
     * {@code JwtAuthenticationEntryPoint} and {@code JwtAccessDeniedHandler}: a 401 or a
     * 403 carried a random id that appeared in no log line anywhere, so the one field
     * whose entire job is to join a client's report to the server's logs was useless on
     * exactly the responses people most often ask about. Both now go through here, so an
     * authentication failure is as traceable as any other error.
     */
    public static String correlationId() {
        String fromMdc = MDC.get(CORRELATION_ID_MDC_KEY);
        return fromMdc != null && !fromMdc.isBlank() ? fromMdc : UUID.randomUUID().toString();
    }
}
