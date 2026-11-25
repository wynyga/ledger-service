package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity {
    @Id
    private UUID id;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long balance;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "system_code", length = 32, unique = true)
    private String systemCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    static AccountEntity from(Account a) {
        AccountEntity e = new AccountEntity();
        e.id = a.id();
        e.ownerId = a.ownerId();
        e.currency = a.currency();
        e.balance = a.balance();
        e.allowNegative = a.allowNegative();
        e.status = a.status();
        e.createdAt = a.createdAt();
        return e;
    }

    Account toDomain() {
        return new Account(id, ownerId, currency, balance, allowNegative, status, createdAt);
    }

    void setBalance(long balance) {
        this.balance = balance;
    }
}
