package com.smartdelivery.delivery.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Ordered just after Spring Boot's {@code ServerHttpObservationFilter} (registered at
 * {@code HIGHEST_PRECEDENCE + 1}), so the request's tracing span already exists by the
 * time this filter tags it -- and still well ahead of Spring Security, so correlation
 * covers every response including 401/403.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private final ObjectProvider<Tracer> tracerProvider;

    public CorrelationIdFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        tagCurrentSpan(correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private void tagCurrentSpan(String correlationId) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return;
        }
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag(MDC_KEY, correlationId);
        }
    }
}
