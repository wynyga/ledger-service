package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.domain.Entry;
import io.github.wynyga.ledger.domain.Transfer;
import io.github.wynyga.ledger.domain.TransferType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class TransferEntity {
    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferType type;

    @Column(name = "debit_account_id", nullable = false)
    private UUID debitAccountId;

    @Column(name = "credit_account_id", nullable = false)
    private UUID creditAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransferEntity() {
    }

    static TransferEntity from(Transfer t) {
        TransferEntity e = new TransferEntity();
        e.id = t.id();
        e.idempotencyKey = t.idempotencyKey();
        e.requestHash = t.requestHash();
        e.type = t.type();
        e.debitAccountId = t.debitAccountId();
        e.creditAccountId = t.creditAccountId();
        e.amount = t.amount();
        e.currency = t.currency();
        e.createdAt = t.createdAt();
        return e;
    }

    Transfer toDomain(List<Entry> entries) {
        return new Transfer(id, idempotencyKey, requestHash, type, debitAccountId, creditAccountId, amount, currency,
                createdAt, entries);
    }

    UUID id() {
        return id;
    }
}
