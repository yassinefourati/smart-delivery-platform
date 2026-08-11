package com.smartdelivery.payment.provider;

import com.smartdelivery.payment.domain.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentProviderTest {

    private final MockPaymentProvider provider = new MockPaymentProvider(new BigDecimal("10000.00"));

    @Test
    void chargesBelowTheThresholdAlwaysSucceed() {
        var result = provider.charge(new BigDecimal("99.99"));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.providerReference()).isNotBlank();
        assertThat(result.declineReason()).isNull();
    }

    @Test
    void chargesAtOrAboveTheThresholdAlwaysDecline() {
        var atThreshold = provider.charge(new BigDecimal("10000.00"));
        var aboveThreshold = provider.charge(new BigDecimal("50000.00"));

        assertThat(atThreshold.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(atThreshold.declineReason()).isNotBlank();
        assertThat(aboveThreshold.status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void outcomeIsDeterministicNotRandom() {
        var first = provider.charge(new BigDecimal("42.00"));
        var second = provider.charge(new BigDecimal("42.00"));

        assertThat(first.status()).isEqualTo(second.status()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void everyChargeGetsAUniqueProviderReference() {
        var first = provider.charge(new BigDecimal("10.00"));
        var second = provider.charge(new BigDecimal("10.00"));

        assertThat(first.providerReference()).isNotEqualTo(second.providerReference());
    }

    @Test
    void refundProducesAUniqueProviderReference() {
        assertThat(provider.refund()).isNotEqualTo(provider.refund());
    }
}
