package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Transfer;
import java.util.Optional;
import java.util.UUID;

/** Port: posted transfers and their entries. */
public interface TransferStore {

    Optional<Transfer> find(UUID id);

    /** Keys are unique per requester. */
    Optional<Transfer> findByIdempotencyKey(UUID requestedBy, String key);

    /**
     * Stores the transfer and its entries.
     *
     * @throws IdempotencyKeyTakenException when the requester already has a transfer with this key
     */
    void insert(Transfer transfer);
}
