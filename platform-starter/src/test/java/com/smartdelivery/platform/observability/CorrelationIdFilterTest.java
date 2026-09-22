package com.smartdelivery.platform.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Six services carried an identical copy of this filter and none of them tested it, which
 * is its own small argument for Phase 19: shared code gets tested once, and duplicated
 * code tends to get tested nowhere.
 */
class CorrelationIdFilterTest {

    @Test
    void generatesAnIdWhenTheClientDidNotSendOne() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        filter(null).doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response,
                capturingChain(seenInsideTheChain));

        assertThat(seenInsideTheChain.get()).isNotNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo(seenInsideTheChain.get());
    }

    /**
     * The id a client supplies must survive unchanged: it is how a caller correlates its
     * own logs with this platform's, and a filter that quietly replaced it would break
     * that without anything failing.
     */
    @Test
    void forwardsAClientSuppliedIdUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-supplied");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter(null).doFilter(request, response, capturingChain(seen));

        assertThat(seen.get()).isEqualTo("client-supplied");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("client-supplied");
    }

    @Test
    void treatsABlankHeaderAsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "   ");
        AtomicReference<String> seen = new AtomicReference<>();

        filter(null).doFilter(request, new MockHttpServletResponse(), capturingChain(seen));

        assertThat(seen.get()).isNotBlank().isNotEqualTo("   ");
        assertThat(UUID.fromString(seen.get())).isNotNull();
    }

    /**
     * The MDC is thread-local and the thread goes back into a pool. Leaving the id behind
     * would stamp the *next* request's log lines with the previous request's id, which is
     * worse than having no correlation id at all.
     */
    @Test
    void clearsTheMdcAfterwardsEvenWhenTheChainThrows() {
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("downstream failure");
        };

        try {
            filter(null).doFilter(new MockHttpServletRequest("GET", "/boom"),
                    new MockHttpServletResponse(), exploding);
        } catch (Exception expected) {
            // the filter must not swallow it
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void tagsTheCurrentTracingSpanWhenThereIsOne() throws Exception {
        Span span = mock(Span.class);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "traced");

        filter(tracer).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        verify(span).tag(eq(CorrelationIdFilter.MDC_KEY), eq("traced"));
    }

    /** Tracing is optional; a service running without it must still correlate its logs. */
    @Test
    void worksWithNoTracerAtAll() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(null).doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response, (req, res) -> {
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
    }

    @Test
    void toleratesATracerWithNoActiveSpan() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(tracer).doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response, (req, res) -> {
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
    }

    // --- helpers ----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private CorrelationIdFilter filter(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return new CorrelationIdFilter(provider);
    }

    /** Records what the MDC held while the rest of the chain ran, not after it finished. */
    private FilterChain capturingChain(AtomicReference<String> sink) {
        return (req, res) -> sink.set(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
