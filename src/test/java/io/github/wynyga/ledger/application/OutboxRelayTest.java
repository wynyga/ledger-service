package io.github.wynyga.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The relay's rules, with in-memory fakes: no database, no broker. */
class OutboxRelayTest {

    static class FakeStore implements OutboxStore {
        final List<OutboxMessage> rows = new ArrayList<>();
        final Set<Long> published = new HashSet<>();
        final Map<Long, Integer> attempts = new HashMap<>();

        void add(int n) {
            for (int i = 0; i < n; i++) {
                long id = rows.size() + 1;
                rows.add(new OutboxMessage(id, UUID.randomUUID(), "TransferPosted", "k" + id, "{}", 0));
            }
        }

        @Override
        public List<OutboxMessage> lockPending(int limit) {
            return rows.stream().filter(m -> !published.contains(m.id())).limit(limit).toList();
        }

        @Override
        public void markPublished(List<Long> ids, Instant at) {
            published.addAll(ids);
        }

        @Override
        public void markFailed(long id, String error) {
            attempts.merge(id, 1, Integer::sum);
        }

        @Override
        public long pendingCount() {
            return rows.size() - published.size();
        }
    }

    static class FakePublisher implements MessagePublisher {
        final List<Long> sent = new ArrayList<>();
        Long failOn;

        @Override
        public void publish(OutboxMessage m) {
            if (failOn != null && failOn == m.id()) {
                throw new PublishFailedException("broker down", null);
            }
            sent.add(m.id());
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-25T05:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final TransactionRunner NO_TX = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };

    final FakeStore store = new FakeStore();
    final FakePublisher publisher = new FakePublisher();
    final MutableClock clock = new MutableClock();

    OutboxRelay relay(int batchSize) {
        return new OutboxRelay(store, publisher, NO_TX, clock, batchSize);
    }

    @Test
    void publishesOldestFirstAndMarksEachRow() {
        store.add(3);

        OutboxRelay.Result r = relay(100).tick();

        assertThat(r.published()).isEqualTo(3);
        assertThat(r.failed()).isFalse();
        assertThat(publisher.sent).containsExactly(1L, 2L, 3L);
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    void aFailureStopsTheBatchSoNothingOvertakesIt() {
        store.add(3);
        publisher.failOn = 2L;

        OutboxRelay.Result r = relay(100).tick();

        assertThat(r.published()).isEqualTo(1);
        assertThat(r.failed()).isTrue();
        assertThat(r.error()).contains("broker down");
        assertThat(publisher.sent).containsExactly(1L);
        assertThat(store.attempts).containsExactly(Map.entry(2L, 1));
        assertThat(store.pendingCount()).isEqualTo(2);
    }

    @Test
    void backsOffWhileTheBrokerIsDownThenCatchesUpInOrder() {
        store.add(3);
        publisher.failOn = 1L;
        OutboxRelay relay = relay(100);

        assertThat(relay.tick().failed()).isTrue();
        assertThat(relay.tick().published()).as("still backing off: nothing tried").isZero();
        assertThat(store.attempts.get(1L)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.tick().failed()).isTrue();
        assertThat(store.attempts.get(1L)).isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        relay.tick();
        assertThat(store.attempts.get(1L)).as("second failure waits 2 s").isEqualTo(2);

        publisher.failOn = null;
        clock.advance(Duration.ofSeconds(1));
        OutboxRelay.Result recovered = relay.tick();
        assertThat(recovered.published()).isEqualTo(3);
        assertThat(publisher.sent).containsExactly(1L, 2L, 3L);

        store.add(1);
        assertThat(relay.tick().published()).as("backoff is reset after a success").isEqualTo(1);
    }

    @Test
    void drainsSeveralBatchesInOneTick() {
        store.add(5);

        assertThat(relay(2).tick().published()).isEqualTo(5);
        assertThat(publisher.sent).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void backoffDoublesUpToAMinute() {
        assertThat(OutboxRelay.backoff(1)).hasSeconds(1);
        assertThat(OutboxRelay.backoff(2)).hasSeconds(2);
        assertThat(OutboxRelay.backoff(3)).hasSeconds(4);
        assertThat(OutboxRelay.backoff(6)).hasSeconds(32);
        assertThat(OutboxRelay.backoff(7)).isEqualTo(OutboxRelay.MAX_BACKOFF);
        assertThat(OutboxRelay.backoff(50)).isEqualTo(OutboxRelay.MAX_BACKOFF);
    }
}
