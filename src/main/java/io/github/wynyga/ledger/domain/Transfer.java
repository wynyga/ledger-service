package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A posted movement of money: always exactly one debit and one credit entry of the same amount.
 *
 * @param requestedBy the user who asked for it; the idempotency key is unique per requester
 */
public record Transfer(UUID id, UUID requestedBy, String idempotencyKey, String requestHash, TransferType type,
        UUID debitAccountId, UUID creditAccountId, long amount, String currency, Instant createdAt,
        List<Entry> entries) {

    public Transfer {
        entries = List.copyOf(entries);
    }
}
