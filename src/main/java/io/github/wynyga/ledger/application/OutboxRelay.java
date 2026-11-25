package io.github.wynyga.ledger.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Moves outbox rows to the broker, oldest first. At least once: a row is marked published only after the broker
 * acknowledged it, so a crash in between sends it again. A failure stops the batch (later rows must not overtake an
 * earlier one) and backs off exponentially, 1 s doubling to 60 s.
 */
public class OutboxRelay {
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    /** Batches per tick at most, so one tick cannot run for ever while the outbox keeps filling. */
    static final int MAX_BATCHES_PER_TICK = 10;

    /** What one tick did. */
    public record Result(int published, boolean failed, String error) {
    }

    private final OutboxStore store;
    private final MessagePublisher publisher;
    private final TransactionRunner tx;
    private final Clock clock;
    private final int batchSize;

    private int consecutiveFailures;
    private Instant nextAttempt = Instant.MIN;

    public OutboxRelay(OutboxStore store, MessagePublisher publisher, TransactionRunner tx, Clock clock,
            int batchSize) {
        this.store = store;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Called by a scheduler. Does nothing while backing off. */
    public synchronized Result tick() {
        if (clock.instant().isBefore(nextAttempt)) {
            return new Result(0, false, null);
        }
        int published = 0;
        for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
            Batch batch = tx.inTransaction(this::relayBatch);
            published += batch.published();
            if (batch.error() != null) {
                consecutiveFailures++;
                nextAttempt = clock.instant().plus(backoff(consecutiveFailures));
                return new Result(published, true, batch.error());
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        consecutiveFailures = 0;
        nextAttempt = Instant.MIN;
        return new Result(published, false, null);
    }

    public long pending() {
        return store.pendingCount();
    }

    /** 1 s, 2 s, 4 s, ... capped at {@link #MAX_BACKOFF}. */
    static Duration backoff(int failures) {
        long seconds = 1L << Math.min(failures - 1, 6);
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private record Batch(int size, int published, String error) {
    }

    private Batch relayBatch() {
        List<OutboxMessage> pending = store.lockPending(batchSize);
        if (pending.isEmpty()) {
            return new Batch(0, 0, null);
        }
        // The whole batch goes out at once (the producer batches it); only the acknowledged prefix is marked.
        MessagePublisher.Outcome outcome = publisher.publishAll(pending);
        int acked = outcome.acknowledged();
        if (acked > 0) {
            store.markPublished(pending.subList(0, acked).stream().map(OutboxMessage::id).toList(), clock.instant());
        }
        if (outcome.failure() != null) {
            RuntimeException e = outcome.failure();
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            store.markFailed(pending.get(acked).id(), error);
            return new Batch(pending.size(), acked, error);
        }
        return new Batch(pending.size(), acked, null);
    }
}
