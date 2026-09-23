package com.smartdelivery.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * The compatibility guarantee, stated as a test rather than only as a promise in
     * ADR 009: the RFC 7807 body is a strict superset of the {@code ErrorResponse} record
     * it replaced. If someone later "tidies up" by dropping the duplicated properties in
     * favour of RFC 7807's own members, this fails -- which is the point, because that
     * would be a breaking change for every existing client.
     */
    @Test
    void carriesEveryFieldTheOldErrorResponseHad() throws Exception {
        MDC.put("correlationId", "corr-1");
        ProblemDetail problem = ApiErrors.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "Order 7 not found", "/api/v1/orders/7");

        Map<String, Object> json = json(problem);
        assertThat(json)
                .containsEntry("status", 404)
                .containsEntry("error", "NOT_FOUND")
                .containsEntry("message", "Order 7 not found")
                .containsEntry("path", "/api/v1/orders/7")
                .containsEntry("correlationId", "corr-1")
                .containsKey("timestamp");
    }

    @Test
    void alsoPopulatesRfc7807sOwnMembers() throws Exception {
        Map<String, Object> json = json(
                ApiErrors.of(HttpStatus.CONFLICT, "CONFLICT", "Already shipped", "/api/v1/orders/7/cancel"));

        assertThat(json)
                .containsEntry("type", ApiErrors.TYPE_BASE)
                .containsEntry("title", "CONFLICT")
                .containsEntry("detail", "Already shipped")
                .containsEntry("instance", "/api/v1/orders/7/cancel");
    }

    /**
     * The 401/403 handlers run inside the security filter chain, where the correlation
     * filter has already populated the MDC. Before Phase 19 they ignored it and minted a
     * random id, so the one field whose job is to join a client's report to a log line
     * matched nothing -- on exactly the responses people most often ask about.
     */
    @Test
    void takesTheCorrelationIdFromTheMdcWhenThereIsOne() {
        MDC.put("correlationId", "from-the-request");
        assertThat(ApiErrors.correlationId()).isEqualTo("from-the-request");
    }

    @Test
    void fallsBackToAFreshIdWhenTheMdcIsEmpty() {
        assertThat(ApiErrors.correlationId()).isNotBlank();
        MDC.put("correlationId", "   ");
        assertThat(ApiErrors.correlationId()).isNotBlank().isNotEqualTo("   ");
    }

    /** A path is optional; an error raised outside a request must still produce a body. */
    @Test
    void toleratesAMissingPath() throws Exception {
        Map<String, Object> json = json(ApiErrors.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "boom", null));
        assertThat(json).containsEntry("status", 500).doesNotContainKey("path");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(ProblemDetail problem) throws Exception {
        // The mixin is what flattens the extension properties up next to RFC 7807's own
        // members instead of nesting them under "properties". Spring Boot registers it on
        // the application ObjectMapper; this test has to do it by hand, and the assertions
        // below are about the flattened shape a client actually receives.
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        return mapper.readValue(mapper.writeValueAsString(problem), Map.class);
    }
}
