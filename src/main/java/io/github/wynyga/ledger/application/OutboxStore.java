package io.github.wynyga.ledger.application;

import java.time.Instant;
import java.util.List;

/** Port: the relay's view of the outbox. All methods run inside the relay's transaction. */
public interface OutboxStore {

    /** Oldest unpublished rows, locked; rows another relay holds are skipped, not waited for. */
    List<OutboxMessage> lockPending(int limit);

    void markPublished(long id, Instant at);

    void markFailed(long id, String error);

    long pendingCount();
}
