package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Transfer;
import java.util.Optional;
import java.util.UUID;

/** Port: posted transfers and their entries. */
public interface TransferStore {

    Optional<Transfer> find(UUID id);

    Optional<Transfer> findByIdempotencyKey(String key);

    /**
     * Stores the transfer and its entries.
     *
     * @throws IdempotencyKeyTakenException when another transfer already holds the idempotency key
     */
    void insert(Transfer transfer);
}
