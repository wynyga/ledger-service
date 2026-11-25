package io.github.wynyga.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.wynyga.ledger.application.AccountService;
import io.github.wynyga.ledger.application.ReconciliationService;
import io.github.wynyga.ledger.application.TransferService;
import io.github.wynyga.ledger.application.TransferService.PostResult;
import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Many transfers at once on a real PostgreSQL: the cases a single-threaded test cannot catch. */
class ConcurrencyTests extends IntegrationTest {
    static final int THREADS = 16;

    @Autowired
    AccountService accounts;
    @Autowired
    TransferService transfers;
    @Autowired
    ReconciliationService reconciliation;

    @Test
    void parallelWithdrawalsNeverOverdraw() throws Exception {
        UUID a = funded(100);
        UUID b = accounts.open("IDR").id();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        runAll(50, i -> () -> {
            try {
                transfers.transfer(key(), a, b, 10, "IDR");
                ok.incrementAndGet();
            } catch (LedgerException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                refused.incrementAndGet();
            }
            return null;
        });

        assertThat(ok).hasValue(10);
        assertThat(refused).hasValue(40);
        assertThat(accounts.get(a).balance()).isZero();
        assertThat(accounts.get(b).balance()).isEqualTo(100);
    }

    @Test
    void sameKeyAtTheSameMomentPaysOnce() throws Exception {
        UUID a = funded(1_000);
        UUID b = accounts.open("IDR").id();
        String k = key();
        Queue<PostResult> results = new ConcurrentLinkedQueue<>();

        runAll(20, i -> () -> results.add(transfers.transfer(k, a, b, 100, "IDR")));

        assertThat(results).hasSize(20);
        assertThat(results).extracting(r -> r.transfer().id()).containsOnly(results.peek().transfer().id());
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(accounts.get(a).balance()).isEqualTo(900);
        assertThat(accounts.get(b).balance()).isEqualTo(100);
    }

    @Test
    void randomTransfersInBothDirectionsKeepTheBooksBalanced() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(funded(10_000));
        }
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        AtomicInteger posted = new AtomicInteger();

        runAll(400, i -> {
            Random rnd = new Random(i);
            UUID from = ids.get(rnd.nextInt(ids.size()));
            UUID to = ids.get((ids.indexOf(from) + 1 + rnd.nextInt(ids.size() - 1)) % ids.size());
            long amount = 1 + rnd.nextInt(2_500);
            return () -> {
                try {
                    transfers.transfer(key(), from, to, amount, "IDR");
                    posted.incrementAndGet();
                } catch (LedgerException e) {
                    if (e.code() != ErrorCode.INSUFFICIENT_FUNDS) {
                        unexpected.add(e);
                    }
                } catch (RuntimeException e) {
                    // A deadlock or lock timeout would land here.
                    unexpected.add(e);
                }
                return null;
            };
        });

        assertThat(unexpected).isEmpty();
        assertThat(posted.get()).isGreaterThan(200);
        List<Account> after = ids.stream().map(accounts::get).toList();
        assertThat(after).allSatisfy(acc -> assertThat(acc.balance()).isNotNegative());
        assertThat(after.stream().mapToLong(Account::balance).sum()).isEqualTo(50_000);
        for (Account acc : after) {
            assertThat(accounts.statement(acc.id(), 1).getFirst().balanceAfter()).isEqualTo(acc.balance());
        }
        assertThat(reconciliation.run().clean()).isTrue();
    }

    // --- helpers

    UUID funded(long amount) {
        UUID id = accounts.open("IDR").id();
        transfers.deposit(key(), id, amount);
        return id;
    }

    static String key() {
        return "conc-" + UUID.randomUUID();
    }

    interface TaskFactory {
        Callable<?> task(int i);
    }

    /** Starts every task at the same moment, to maximise contention, and rethrows the first failure. */
    static void runAll(int count, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<?> task = factory.task(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
