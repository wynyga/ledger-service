package io.github.wynyga.ledger.application;

import java.time.Instant;
import java.util.List;

/** Port: the relay's view of the outbox. All methods run inside the relay's transaction. */
public interface OutboxStore {

    /** Oldest unpublished rows, locked; rows another relay holds are skipped, not waited for. */
    List<OutboxMessage> lockPending(int limit);

    /** One statement for the whole batch. */
    void markPublished(List<Long> ids, Instant at);

    void markFailed(long id, String error);

    long pendingCount();
}
