package com.smartdelivery.payment.service;

import com.smartdelivery.payment.domain.Payment;
import com.smartdelivery.payment.domain.PaymentStatus;
import com.smartdelivery.payment.domain.TransactionStatus;
import com.smartdelivery.payment.dto.ChargeRequest;
import com.smartdelivery.payment.dto.RefundRequest;
import com.smartdelivery.payment.exception.InvalidPaymentStateException;
import com.smartdelivery.payment.exception.PaymentNotFoundException;
import com.smartdelivery.payment.provider.ChargeResult;
import com.smartdelivery.payment.provider.MockPaymentProvider;
import com.smartdelivery.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private MockPaymentProvider paymentProvider;

    private PaymentService service() {
        return new PaymentService(paymentRepository, paymentProvider);
    }

    private Payment paymentWithStatus(UUID orderId, PaymentStatus status) {
        Payment payment = new Payment(orderId, new BigDecimal("50.00"), "USD");
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @Test
    void chargeCreatesAPaymentAndRecordsASuccessfulOutcome() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        var request = new ChargeRequest(orderId, new BigDecimal("50.00"), "USD");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(paymentProvider.charge(request.amount())).thenReturn(new ChargeResult(TransactionStatus.SUCCESS, "ref-1", null));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment payment = service.charge(request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getOrderId()).isEqualTo(orderId);
    }

    @Test
    void chargeRecordsADeclinedOutcomeWithoutThrowing() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        var request = new ChargeRequest(orderId, new BigDecimal("50000.00"), "USD");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(paymentProvider.charge(request.amount())).thenReturn(new ChargeResult(TransactionStatus.FAILED, "ref-2", "DECLINED_AMOUNT_LIMIT"));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment payment = service.charge(request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void repeatedChargeForTheSameOrderIsIdempotentAndDoesNotCallTheProviderAgain() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        Payment existing = paymentWithStatus(orderId, PaymentStatus.SUCCESS);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        Payment result = service.charge(new ChargeRequest(orderId, new BigDecimal("50.00"), "USD"));

        assertThat(result).isSameAs(existing);
        verify(paymentProvider, never()).charge(any());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        PaymentService service = service();
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void refundThrowsWhenNoPaymentExistsForTheOrder() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(new RefundRequest(orderId)))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void refundingASuccessfulPaymentMarksItRefunded() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        Payment payment = paymentWithStatus(orderId, PaymentStatus.SUCCESS);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
        when(paymentProvider.refund()).thenReturn("refund-ref-1");

        Payment result = service.refund(new RefundRequest(orderId));

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refundingAnAlreadyRefundedPaymentIsAnIdempotentNoOp() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        Payment payment = paymentWithStatus(orderId, PaymentStatus.REFUNDED);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        Payment result = service.refund(new RefundRequest(orderId));

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentProvider, never()).refund();
    }

    @Test
    void refundingAPaymentThatWasNeverSuccessfulThrows() {
        PaymentService service = service();
        UUID orderId = UUID.randomUUID();
        Payment payment = paymentWithStatus(orderId, PaymentStatus.FAILED);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.refund(new RefundRequest(orderId)))
                .isInstanceOf(InvalidPaymentStateException.class);
    }
}
