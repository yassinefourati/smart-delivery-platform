package com.smartdelivery.payment.provider;

import com.smartdelivery.payment.domain.TransactionStatus;

public record ChargeResult(TransactionStatus status, String providerReference, String declineReason) {
}
