package io.github.wynyga.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.wynyga.ledger.application.AccountService;
import io.github.wynyga.ledger.application.AuthService;
import io.github.wynyga.ledger.application.Caller;
import io.github.wynyga.ledger.application.HoldService;
import io.github.wynyga.ledger.application.UserStore;
import io.github.wynyga.ledger.application.ReconciliationService;
import io.github.wynyga.ledger.application.TransferService;
import io.github.wynyga.ledger.application.TransferService.PostResult;
import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    @Autowired
    AuthService auth;
    @Autowired
    UserStore users;
    @Autowired
    HoldService holds;

    @Test
    void holdsTransfersCapturesAndReleasesRacingNeverOverspend() throws Exception {
        Caller alice = newUser();
        Caller shop = newUser();
        UUID wallet = funded(alice, 1_000);
        UUID till = accounts.open(shop, "IDR").id();
        Queue<UUID> authorized = new ConcurrentLinkedQueue<>();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        // 60 operations of 100 against 1,000: half reserve, half spend directly. At most 10 can win.
        runAll(60, i -> () -> {
            try {
                if (i % 2 == 0) {
                    authorized.add(holds.authorize(alice, key(), wallet, till, 100, "IDR", null).hold().id());
                } else {
                    transfers.transfer(alice, key(), wallet, till, 100, "IDR");
                }
            } catch (LedgerException e) {
                if (e.code() != ErrorCode.INSUFFICIENT_FUNDS) {
                    unexpected.add(e);
                }
            } catch (RuntimeException e) {
                unexpected.add(e);
            }
            return null;
        });
        assertThat(unexpected).isEmpty();
        Account mid = accounts.get(admin(), wallet);
        assertThat(mid.available()).isZero();
        assertThat(mid.held()).isEqualTo(100L * authorized.size());

        // Now settle every hold twice at once: once by capture, once by release. Exactly one of each pair wins.
        List<UUID> ids = List.copyOf(authorized);
        runAll(ids.size() * 2, i -> () -> {
            UUID id = ids.get(i / 2);
            try {
                if (i % 2 == 0) {
                    holds.capture(shop, id, 60L);
                } else {
                    holds.release(alice, id);
                }
            } catch (LedgerException e) {
                if (e.code() != ErrorCode.HOLD_NOT_ACTIVE) {
                    unexpected.add(e);
                }
            } catch (RuntimeException e) {
                unexpected.add(e);
            }
            return null;
        });

        assertThat(unexpected).isEmpty();
        Account after = accounts.get(admin(), wallet);
        assertThat(after.held()).isZero();
        assertThat(after.balance() + accounts.get(admin(), till).balance()).isEqualTo(1_000);
        assertThat(after.balance()).isNotNegative();
        assertThat(reconciliation.run(admin()).clean()).isTrue();
    }

    @Test
    void parallelWithdrawalsNeverOverdraw() throws Exception {
        Caller alice = newUser();
        UUID a = funded(alice, 100);
        UUID b = accounts.open(newUser(), "IDR").id();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        runAll(50, i -> () -> {
            try {
                transfers.transfer(alice, key(), a, b, 10, "IDR");
                ok.incrementAndGet();
            } catch (LedgerException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                refused.incrementAndGet();
            }
            return null;
        });

        assertThat(ok).hasValue(10);
        assertThat(refused).hasValue(40);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void sameKeyAtTheSameMomentPaysOnce() throws Exception {
        Caller alice = newUser();
        UUID a = funded(alice, 1_000);
        UUID b = accounts.open(newUser(), "IDR").id();
        String k = key();
        Queue<PostResult> results = new ConcurrentLinkedQueue<>();

        runAll(20, i -> () -> results.add(transfers.transfer(alice, k, a, b, 100, "IDR")));

        assertThat(results).hasSize(20);
        assertThat(results).extracting(r -> r.transfer().id()).containsOnly(results.peek().transfer().id());
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void randomTransfersInBothDirectionsKeepTheBooksBalanced() throws Exception {
        List<UUID> ids = new ArrayList<>();
        Map<UUID, Caller> owners = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            Caller owner = newUser();
            UUID id = funded(owner, 10_000);
            ids.add(id);
            owners.put(id, owner);
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
                    transfers.transfer(owners.get(from), key(), from, to, amount, "IDR");
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
        List<Account> after = ids.stream().map(id -> accounts.get(admin(), id)).toList();
        assertThat(after).allSatisfy(acc -> assertThat(acc.balance()).isNotNegative());
        assertThat(after.stream().mapToLong(Account::balance).sum()).isEqualTo(50_000);
        for (Account acc : after) {
            assertThat(accounts.statement(admin(), acc.id(), 1).getFirst().balanceAfter()).isEqualTo(acc.balance());
        }
        assertThat(reconciliation.run(admin()).clean()).isTrue();
    }

    // --- helpers

    Caller newUser() {
        String name = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return new Caller(auth.register(name, PASSWORD).id(), false);
    }

    Caller admin() {
        return new Caller(users.findByUsername(ADMIN).orElseThrow().id(), true);
    }

    UUID funded(Caller owner, long amount) {
        UUID id = accounts.open(owner, "IDR").id();
        transfers.deposit(admin(), key(), id, amount);
        return id;
    }

    long balance(UUID account) {
        return accounts.get(admin(), account).balance();
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
