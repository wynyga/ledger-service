package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.domain.Hold;
import io.github.wynyga.ledger.domain.HoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "holds")
public class HoldEntity {
    @Id
    private UUID id;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "debit_account_id", nullable = false)
    private UUID payerAccountId;

    @Column(name = "credit_account_id", nullable = false)
    private UUID receiverAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HoldStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "captured_amount")
    private Long capturedAmount;

    @Column(name = "capture_transfer_id")
    private UUID captureTransferId;

    protected HoldEntity() {
    }

    static HoldEntity from(Hold h) {
        HoldEntity e = new HoldEntity();
        e.id = h.id();
        e.requestedBy = h.requestedBy();
        e.idempotencyKey = h.idempotencyKey();
        e.requestHash = h.requestHash();
        e.payerAccountId = h.payerAccountId();
        e.receiverAccountId = h.receiverAccountId();
        e.amount = h.amount();
        e.currency = h.currency();
        e.createdAt = h.createdAt();
        e.expiresAt = h.expiresAt();
        e.copyState(h);
        return e;
    }

    /** The fields that change after authorisation. */
    void copyState(Hold h) {
        status = h.status();
        settledAt = h.settledAt();
        capturedAmount = h.capturedAmount();
        captureTransferId = h.captureTransferId();
    }

    Hold toDomain() {
        return new Hold(id, requestedBy, idempotencyKey, requestHash, payerAccountId, receiverAccountId, amount,
                currency, status, createdAt, expiresAt, settledAt, capturedAmount, captureTransferId);
    }
}
