package com.smartdelivery.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.platform.web.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers a request to a protected endpoint that carried no usable JWT. Like
 * {@link JwtAccessDeniedHandler}, this runs inside the security filter chain and so
 * cannot rely on the {@code @RestControllerAdvice}; it produces the same body directly.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ProblemDetail body = ApiErrors.of(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Authentication is required to access this resource",
                request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
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
