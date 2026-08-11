package com.smartdelivery.payment.web;

import com.smartdelivery.payment.domain.Payment;
import com.smartdelivery.payment.domain.PaymentStatus;
import com.smartdelivery.payment.dto.ChargeRequest;
import com.smartdelivery.payment.dto.PaymentResponse;
import com.smartdelivery.payment.dto.RefundRequest;
import com.smartdelivery.payment.event.PaymentCompletedPayload;
import com.smartdelivery.payment.event.PaymentEventPublisher;
import com.smartdelivery.payment.event.PaymentFailedPayload;
import com.smartdelivery.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Mock payment processing (ADMIN / SERVICE only)")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentEventPublisher eventPublisher;

    public PaymentController(PaymentService paymentService, PaymentEventPublisher eventPublisher) {
        this.paymentService = paymentService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Always returns 201 with the outcome in the response body's {@code status} --
     * a card/processor decline is a successfully processed payment *attempt*, not an
     * HTTP-level error. Callers (order-service's saga) branch on {@code status}, not
     * on the HTTP status code. See docs/saga.md.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> charge(@Valid @RequestBody ChargeRequest request) {
        Payment payment = paymentService.charge(request);
        publishOutcome(payment);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.getById(id)));
    }

    @PostMapping("/refund")
    public ResponseEntity<PaymentResponse> refund(@Valid @RequestBody RefundRequest request) {
        Payment payment = paymentService.refund(request);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    private void publishOutcome(Payment payment) {
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            eventPublisher.publishCompleted(new PaymentCompletedPayload(payment.getOrderId(), payment.getId(), payment.getAmount()));
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            eventPublisher.publishFailed(new PaymentFailedPayload(payment.getOrderId(), "Payment declined"));
        }
    }
}
