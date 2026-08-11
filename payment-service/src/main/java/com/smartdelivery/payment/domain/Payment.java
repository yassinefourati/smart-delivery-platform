package com.smartdelivery.payment.domain;

import com.smartdelivery.payment.exception.InvalidPaymentStateException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<PaymentTransaction> transactions = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(UUID orderId, BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
    }

    public PaymentTransaction recordCharge(TransactionStatus outcome, String providerReference) {
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateException("charge", status);
        }
        status = outcome == TransactionStatus.SUCCESS ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
        return addTransaction(TransactionType.CHARGE, outcome, providerReference);
    }

    public PaymentTransaction recordRefund(String providerReference) {
        if (status != PaymentStatus.SUCCESS) {
            throw new InvalidPaymentStateException("refund", status);
        }
        status = PaymentStatus.REFUNDED;
        return addTransaction(TransactionType.REFUND, TransactionStatus.SUCCESS, providerReference);
    }

    private PaymentTransaction addTransaction(TransactionType type, TransactionStatus outcome, String providerReference) {
        PaymentTransaction transaction = new PaymentTransaction(this, type, outcome, providerReference);
        transactions.add(transaction);
        return transaction;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public List<PaymentTransaction> getTransactions() {
        return transactions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
