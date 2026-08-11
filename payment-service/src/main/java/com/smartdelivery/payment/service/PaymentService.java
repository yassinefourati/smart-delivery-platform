package com.smartdelivery.payment.service;

import com.smartdelivery.payment.domain.Payment;
import com.smartdelivery.payment.domain.PaymentStatus;
import com.smartdelivery.payment.dto.ChargeRequest;
import com.smartdelivery.payment.dto.RefundRequest;
import com.smartdelivery.payment.event.PaymentCompletedPayload;
import com.smartdelivery.payment.event.PaymentEventPublisher;
import com.smartdelivery.payment.event.PaymentFailedPayload;
import com.smartdelivery.payment.exception.PaymentNotFoundException;
import com.smartdelivery.payment.provider.ChargeResult;
import com.smartdelivery.payment.provider.MockPaymentProvider;
import com.smartdelivery.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MockPaymentProvider paymentProvider;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository, MockPaymentProvider paymentProvider, PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Idempotent per orderId (UNIQUE constraint on payments.order_id): a retried
     * charge request for an order that's already been charged -- e.g. order-service's
     * saga listener retrying the whole step after an unrelated later failure, see
     * docs/saga.md -- returns the original outcome instead of charging twice (and
     * without re-announcing an outcome that was already announced the first time).
     * The outbox write happens here, inside the same transaction as the charge.
     */
    @Transactional
    public Payment charge(ChargeRequest request) {
        var existing = paymentRepository.findByOrderId(request.orderId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Payment payment = new Payment(request.orderId(), request.amount(), request.currency());
        ChargeResult result = paymentProvider.charge(request.amount());
        payment.recordCharge(result.status(), result.providerReference());

        Payment saved;
        try {
            saved = paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // Another concurrent charge request for the exact same order won the
            // unique-constraint race after our existence check above.
            return paymentRepository.findByOrderId(request.orderId()).orElseThrow(() -> e);
        }
        publishOutcome(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public Payment getById(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> PaymentNotFoundException.byId(id));
    }

    @Transactional
    public Payment refund(RefundRequest request) {
        Payment payment = paymentRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> PaymentNotFoundException.byOrderId(request.orderId()));

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }

        String reference = paymentProvider.refund();
        payment.recordRefund(reference);
        return payment;
    }

    private void publishOutcome(Payment payment) {
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            eventPublisher.publishCompleted(new PaymentCompletedPayload(payment.getOrderId(), payment.getId(), payment.getAmount()));
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            eventPublisher.publishFailed(new PaymentFailedPayload(payment.getOrderId(), "Payment declined"));
        }
    }
}
