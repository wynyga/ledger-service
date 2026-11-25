package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A posted movement of money: always exactly one debit and one credit entry of the same amount. */
public record Transfer(UUID id, String idempotencyKey, String requestHash, TransferType type, UUID debitAccountId,
        UUID creditAccountId, long amount, String currency, Instant createdAt, List<Entry> entries) {

    public Transfer {
        entries = List.copyOf(entries);
    }
}
