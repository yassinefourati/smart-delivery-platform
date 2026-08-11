package com.smartdelivery.payment.provider;

import com.smartdelivery.payment.domain.TransactionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Section 10 of the master brief: "Simulate payment success/failure in a
 * deterministic way suitable for testing. Do not integrate a real payment provider
 * unless explicitly requested."
 *
 * The rule is deliberately simple and deterministic rather than random: any charge at
 * or above {@code payment.decline-threshold} is declined, simulating a processor
 * limit/fraud check on unusually large charges; everything below it succeeds. This
 * gives both a plausible business justification and, just as importantly, a reliable
 * lever for tests to pick an amount and know exactly which branch they're exercising
 * -- no flaky randomness, no magic "unlucky number."
 */
@Component
public class MockPaymentProvider {

    private final BigDecimal declineThreshold;

    public MockPaymentProvider(@Value("${payment.decline-threshold}") BigDecimal declineThreshold) {
        this.declineThreshold = declineThreshold;
    }

    public ChargeResult charge(BigDecimal amount) {
        String reference = "mock-charge-" + UUID.randomUUID();
        if (amount.compareTo(declineThreshold) >= 0) {
            return new ChargeResult(TransactionStatus.FAILED, reference, "DECLINED_AMOUNT_LIMIT");
        }
        return new ChargeResult(TransactionStatus.SUCCESS, reference, null);
    }

    public String refund() {
        return "mock-refund-" + UUID.randomUUID();
    }
}
