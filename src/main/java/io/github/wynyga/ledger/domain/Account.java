package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An account with its current balance in minor units. The balance only changes through {@link Ledger#post}, which
 * writes the matching entries, so the stored balance can always be checked against the entries.
 *
 * <p>{@code held} is the part of the balance reserved by active holds. It cannot be spent: what a debit may use is
 * {@link #available()} = balance - held.
 */
public final class Account {
    private final UUID id;
    private final UUID ownerId;
    private final String currency;
    private long balance;
    private long held;
    private final boolean allowNegative;
    private final AccountStatus status;
    private final Instant createdAt;

    /** @param ownerId the user who owns the account; null for system accounts */
    public Account(UUID id, UUID ownerId, String currency, long balance, long held, boolean allowNegative,
            AccountStatus status, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = ownerId;
        this.currency = Currencies.normalize(currency);
        this.balance = balance;
        if (held < 0) {
            throw new IllegalArgumentException("held cannot be negative");
        }
        this.held = held;
        this.allowNegative = allowNegative;
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** A new customer account: empty, active, never allowed below zero. */
    public static Account open(UUID id, UUID ownerId, String currency, Instant now) {
        return new Account(id, Objects.requireNonNull(ownerId), currency, 0, 0, false, AccountStatus.ACTIVE, now);
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId != null && ownerId.equals(userId);
    }

    /** What a debit or a new hold may use. */
    public long available() {
        return balance - held;
    }

    long balanceAfterDebit(long amount) {
        ensureActive();
        long next;
        try {
            next = Math.subtractExact(balance, amount);
        } catch (ArithmeticException e) {
            throw new LedgerException(ErrorCode.BALANCE_OVERFLOW, "balance would overflow");
        }
        if (next - held < 0 && !allowNegative) {
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

    void reserve(long amount) {
        ensureActive();
        long next;
        try {
            next = Math.addExact(held, amount);
        } catch (ArithmeticException e) {
            throw new LedgerException(ErrorCode.BALANCE_OVERFLOW, "held amount would overflow");
        }
        if (balance - next < 0 && !allowNegative) {
            throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, "insufficient funds");
        }
        held = next;
    }

    void releaseReserve(long amount) {
        if (amount > held) {
            throw new IllegalStateException("releasing " + amount + " but only " + held + " is held on " + id);
        }
        held -= amount;
    }

    /** Undoes {@link #releaseReserve} when the step after it was refused. */
    void restoreReserve(long amount) {
        held += amount;
    }

    void ensureActive() {
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

    public long held() {
        return held;
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
