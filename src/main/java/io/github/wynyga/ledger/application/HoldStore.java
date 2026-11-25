package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Hold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: holds. */
public interface HoldStore {

    /** @throws IdempotencyKeyTakenException when the requester already has a hold with this key */
    void insert(Hold hold);

    Optional<Hold> find(UUID id);

    /** Loads and locks the hold until the transaction ends. Lock accounts before calling this. */
    Optional<Hold> lock(UUID id);

    Optional<Hold> findByIdempotencyKey(UUID requestedBy, String key);

    /** Writes status, settlement time and capture details. Only inside the transaction that locked it. */
    void update(Hold hold);

    /** Active holds whose expiry has passed, oldest first. Not locked. */
    List<UUID> dueForExpiry(Instant now, int limit);
}
