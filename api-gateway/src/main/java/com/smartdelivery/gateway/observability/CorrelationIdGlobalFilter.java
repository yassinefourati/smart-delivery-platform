package com.smartdelivery.gateway.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The origin of every request's correlation id (docs/observability.md): forwards
 * whatever {@code X-Correlation-Id} the client already supplied, or generates a fresh
 * one if they didn't, onto every proxied backend call -- and echoes the same value back
 * on the response, so a client that didn't send one can still find it (in logs, in
 * follow-up support requests) after the fact. Every backend service's own
 * {@code CorrelationIdFilter} then just reads what's already here rather than
 * generating its own, keeping one id for the whole request across every service it
 * touches.
 *
 * Logs the mapping directly, rather than relying on {@code logging.pattern.level}'s MDC
 * placeholder the way every backend service does: WebFlux doesn't execute one request
 * on one dedicated thread, so a plain {@code MDC.put()} here wouldn't reliably still be
 * visible by the time a later log statement for the same request runs on a different
 * Reactor thread. One explicit log line per request, right here, sidesteps that
 * entirely instead of reaching for Reactor Context propagation machinery this gateway
 * doesn't otherwise need.
 *
 * For the same reason, this filter does not attempt to tag the current tracing span with
 * the correlation id the way each backend service's {@code CorrelationIdFilter} does --
 * a synchronous {@code Tracer.currentSpan()} call here would depend on exactly the same
 * thread-local/Reactor-context handoff this class already avoids for logging. The
 * correlation id and the OTel trace id both end up in every service's structured logs
 * regardless, so the two remain cross-referenceable without it.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    public static final String HEADER_NAME = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = request.getHeaders().getFirst(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        log.info("{} {} correlationId={}", request.getMethod(), request.getPath(), correlationId);

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(HEADER_NAME, correlationId)
                .build();
        exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
