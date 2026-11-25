package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** One side of a transfer. Entries are append-only; {@code balanceAfter} is the account balance once it applied. */
public record Entry(UUID transferId, UUID accountId, Direction direction, long amount, long balanceAfter,
        Instant createdAt) {

    /** The entry's effect on its account: negative for a debit, positive for a credit. */
    public long signedAmount() {
        return direction == Direction.CREDIT ? amount : -amount;
    }
}
