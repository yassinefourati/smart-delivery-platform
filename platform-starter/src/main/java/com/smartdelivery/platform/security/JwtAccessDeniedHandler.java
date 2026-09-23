package com.smartdelivery.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.platform.web.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers an authenticated-but-not-permitted request. Runs inside the security filter
 * chain, before Spring MVC dispatch, so it cannot go through the
 * {@code @RestControllerAdvice}; it writes the identical body directly instead, which
 * is exactly why that body's construction lives in {@link ApiErrors} rather than in the
 * advice.
 */
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemDetail body = ApiErrors.of(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "You do not have permission to perform this action",
                request.getRequestURI());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Explicit, because getWriter() otherwise encodes with the servlet default of
        // ISO-8859-1 and advertises it in the Content-Type -- so any non-ASCII in an error
        // message came back mangled, and a client comparing the header to
        // "application/problem+json" exactly would not match. The MVC advice path never
        // had this: Spring's message converter writes UTF-8. Only these two handlers,
        // which run inside the security filter chain and serialize the body themselves,
        // were affected. Found while building the frontend (Phase 21).
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
