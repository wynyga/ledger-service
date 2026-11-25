package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.domain.Direction;
import io.github.wynyga.ledger.domain.Entry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entries")
public class EntryEntity {
    /** Insertion order; statements sort by it. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EntryEntity() {
    }

    static EntryEntity from(Entry en) {
        EntryEntity e = new EntryEntity();
        e.transferId = en.transferId();
        e.accountId = en.accountId();
        e.direction = en.direction();
        e.amount = en.amount();
        e.balanceAfter = en.balanceAfter();
        e.createdAt = en.createdAt();
        return e;
    }

    Entry toDomain() {
        return new Entry(transferId, accountId, direction, amount, balanceAfter, createdAt);
    }
}
