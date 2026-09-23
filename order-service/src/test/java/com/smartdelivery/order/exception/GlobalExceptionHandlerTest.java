package com.smartdelivery.order.exception;

import com.smartdelivery.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order-service's own error codes -- the ones a client has to branch on. The shared
 * mappings inherited from {@code PlatformExceptionHandler} are covered in platform-starter;
 * what matters here is that this service's 409s are distinguishable from each other.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * The distinction this test exists for. Both are 409, and a client has to tell them
     * apart: reusing an Idempotency-Key with a different body is a bug in the caller to
     * fix, while an illegal state transition is a state the caller must re-read and show.
     * Until Phase 21 both came back as the generic CONFLICT, even though
     * docs/order-flow.md had promised IDEMPOTENCY_KEY_CONFLICT since Phase 7 -- so the
     * documented contract was right and the code was the defect.
     */
    @Test
    void anIdempotencyKeyConflictIsDistinguishableFromEveryOther409() throws Exception {
        mockMvc.perform(get("/probe/idempotency-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.title").value("IDEMPOTENCY_KEY_CONFLICT"));

        mockMvc.perform(get("/probe/bad-transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void anUnknownProductIsABadRequestNotANotFound() throws Exception {
        // 400, not 404: the ORDER is the resource being created and the request is what is
        // wrong with it. A 404 would imply the order endpoint does not exist.
        mockMvc.perform(get("/probe/unknown-product"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PRODUCT"));
    }

    @Test
    void anOptimisticLockLossTellsTheCallerToRetry() throws Exception {
        mockMvc.perform(get("/probe/concurrent"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("retry")));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/idempotency-conflict")
        String idempotencyConflict() {
            throw new IdempotencyKeyConflictException("checkout-7f3a-1");
        }

        @GetMapping("/probe/bad-transition")
        String badTransition() {
            throw new InvalidOrderStateTransitionException(OrderStatus.SHIPMENT_CREATED, OrderStatus.CANCELLED);
        }

        @GetMapping("/probe/unknown-product")
        String unknownProduct() {
            throw new ProductNotFoundException(UUID.randomUUID());
        }

        @GetMapping("/probe/concurrent")
        String concurrent() {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Object.class, "id");
        }
    }
}
