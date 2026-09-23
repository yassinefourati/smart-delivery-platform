package com.smartdelivery.platform.autoconfigure;

import com.smartdelivery.platform.observability.CorrelationIdFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers the correlation-id filter every servlet service in this platform used to
 * carry its own identical copy of (docs/observability.md).
 *
 * <p>{@code @ConditionalOnWebApplication(SERVLET)} is load-bearing, not defensive:
 * api-gateway is reactive and has its own {@code CorrelationIdGlobalFilter}, because a
 * WebFlux request is not pinned to one thread and an {@code MDC.put} there would not
 * survive to the next log statement. The two implementations are genuinely different
 * code, not duplication, and this condition is what keeps the servlet one from being
 * offered where it cannot work.
 */
@AutoConfiguration
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformWebAutoConfiguration {

    /**
     * The filter's position in the chain comes from the {@code @Order} on
     * {@link CorrelationIdFilter} itself -- Spring Boot sorts plain {@code Filter} beans
     * with {@code AnnotationAwareOrderComparator}, which reads the annotation off the
     * instance's class. It is declared there rather than here so the ordering rationale
     * sits next to the code it constrains.
     */
    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter(ObjectProvider<Tracer> tracerProvider) {
        return new CorrelationIdFilter(tracerProvider);
    }
}
