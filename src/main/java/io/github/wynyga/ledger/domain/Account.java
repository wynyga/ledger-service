package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An account with its current balance in minor units. The balance only changes through {@link Ledger#post}, which
 * writes the matching entries, so the stored balance can always be checked against the entries.
 */
public final class Account {
    private final UUID id;
    private final UUID ownerId;
    private final String currency;
    private long balance;
    private final boolean allowNegative;
    private final AccountStatus status;
    private final Instant createdAt;

    /** @param ownerId the user who owns the account; null for system accounts */
    public Account(UUID id, UUID ownerId, String currency, long balance, boolean allowNegative, AccountStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = ownerId;
        this.currency = Currencies.normalize(currency);
        this.balance = balance;
        this.allowNegative = allowNegative;
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** A new customer account: empty, active, never allowed below zero. */
    public static Account open(UUID id, UUID ownerId, String currency, Instant now) {
        return new Account(id, Objects.requireNonNull(ownerId), currency, 0, false, AccountStatus.ACTIVE, now);
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId != null && ownerId.equals(userId);
    }

    long balanceAfterDebit(long amount) {
        ensureActive();
        long next;
        try {
            next = Math.subtractExact(balance, amount);
        } catch (ArithmeticException e) {
            throw new LedgerException(ErrorCode.BALANCE_OVERFLOW, "balance would overflow");
        }
        if (next < 0 && !allowNegative) {
            throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, "insufficient funds");
        }
        return next;
    }

    long balanceAfterCredit(long amount) {
        ensureActive();
        try {
            return Math.addExact(balance, amount);
        } catch (ArithmeticException e) {
            throw new LedgerException(ErrorCode.BALANCE_OVERFLOW, "balance would overflow");
        }
    }

    void apply(long newBalance) {
        this.balance = newBalance;
    }

    private void ensureActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new LedgerException(ErrorCode.ACCOUNT_INACTIVE, "account " + id + " is not active");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String currency() {
        return currency;
    }

    public long balance() {
        return balance;
    }

    public boolean allowNegative() {
        return allowNegative;
    }

    public AccountStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
