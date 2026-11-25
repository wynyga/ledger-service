package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Account;
import java.util.UUID;

/**
 * Locks two accounts in a fixed order (by id), so that any two operations touching the same pair queue instead of
 * deadlocking. Every use case that locks more than one row locks accounts first, in this order, and a hold last.
 */
final class AccountLocks {
    private AccountLocks() {
    }

    record Pair(Account a, Account b) {
        Account get(UUID id) {
            if (a.id().equals(id)) {
                return a;
            }
            if (b.id().equals(id)) {
                return b;
            }
            throw new IllegalArgumentException("account " + id + " is not part of this pair");
        }
    }

    static Pair lock(AccountStore accounts, UUID x, UUID y) {
        UUID first = x.compareTo(y) < 0 ? x : y;
        UUID second = first.equals(x) ? y : x;
        Account a = accounts.lock(first).orElseThrow(() -> AccountService.notFound(first));
        Account b = accounts.lock(second).orElseThrow(() -> AccountService.notFound(second));
        return new Pair(a, b);
    }
}
