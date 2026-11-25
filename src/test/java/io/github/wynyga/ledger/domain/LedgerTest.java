package io.github.wynyga.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTest {
    static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    static final UUID OWNER = UUID.randomUUID();

    static Account account(long balance) {
        return new Account(UUID.randomUUID(), OWNER, "IDR", balance, false, AccountStatus.ACTIVE, NOW);
    }

    static TransferCommand cmd(Account from, Account to, long amount) {
        return new TransferCommand(TransferType.TRANSFER, from.id(), to.id(), amount, "IDR");
    }

    @Test
    void postsOneDebitAndOneCreditOfTheSameAmount() {
        Account a = account(100_000);
        Account b = account(5_000);
        UUID id = UUID.randomUUID();

        Transfer t = Ledger.post(cmd(a, b, 30_000), OWNER, "k-1", a, b, id, NOW);

        assertThat(a.balance()).isEqualTo(70_000);
        assertThat(b.balance()).isEqualTo(35_000);
        assertThat(t.id()).isEqualTo(id);
        assertThat(t.entries()).containsExactly(
                new Entry(id, a.id(), Direction.DEBIT, 30_000, 70_000, NOW),
                new Entry(id, b.id(), Direction.CREDIT, 30_000, 35_000, NOW));
        assertThat(t.entries().stream().mapToLong(Entry::signedAmount).sum()).isZero();
    }

    @Test
    void refusesOverdraftAndChangesNothing() {
        Account a = account(10);
        Account b = account(0);

        assertThatThrownBy(() -> Ledger.post(cmd(a, b, 11), OWNER, "k", a, b, UUID.randomUUID(), NOW))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(a.balance()).isEqualTo(10);
        assertThat(b.balance()).isZero();
    }

    @Test
    void fundingAccountMayGoNegative() {
        Account funding = new Account(UUID.randomUUID(), null, "IDR", 0, true, AccountStatus.ACTIVE, NOW);
        Account b = account(0);

        Ledger.post(new TransferCommand(TransferType.DEPOSIT, funding.id(), b.id(), 500, "IDR"), OWNER, "d", funding, b,
                UUID.randomUUID(), NOW);

        assertThat(funding.balance()).isEqualTo(-500);
        assertThat(b.balance()).isEqualTo(500);
    }

    @Test
    void creditOverflowLeavesTheDebitSideUntouched() {
        Account a = account(1_000);
        Account full = account(Long.MAX_VALUE - 5);

        assertThatThrownBy(() -> Ledger.post(cmd(a, full, 10), OWNER, "k", a, full, UUID.randomUUID(), NOW))
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.BALANCE_OVERFLOW);
        assertThat(a.balance()).isEqualTo(1_000);
    }

    @Test
    void refusesCurrencyMismatch() {
        Account a = account(1_000);
        Account usd = new Account(UUID.randomUUID(), OWNER, "USD", 0, false, AccountStatus.ACTIVE, NOW);

        assertThatThrownBy(() -> Ledger.post(cmd(a, usd, 10), OWNER, "k", a, usd, UUID.randomUUID(), NOW))
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void refusesFrozenAccounts() {
        Account a = account(1_000);
        Account frozen = new Account(UUID.randomUUID(), OWNER, "IDR", 0, false, AccountStatus.FROZEN, NOW);

        assertThatThrownBy(() -> Ledger.post(cmd(a, frozen, 10), OWNER, "k", a, frozen, UUID.randomUUID(), NOW))
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
        assertThat(a.balance()).isEqualTo(1_000);
    }

    @Test
    void commandRejectsBadAmountsAndSameAccount() {
        Account a = account(0);
        Account b = account(0);
        assertThatThrownBy(() -> cmd(a, b, 0))
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.INVALID_AMOUNT);
        assertThatThrownBy(() -> cmd(a, b, Ledger.MAX_AMOUNT + 1))
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.INVALID_AMOUNT);
        assertThatThrownBy(() -> cmd(a, a, 10))
                .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.SAME_ACCOUNT);
    }

    @Test
    void requestHashIdentifiesTheRequest() {
        Account a = account(0);
        Account b = account(0);
        assertThat(cmd(a, b, 10).hash()).isEqualTo(cmd(a, b, 10).hash()).hasSize(64);
        assertThat(cmd(a, b, 10).hash()).isNotEqualTo(cmd(a, b, 11).hash());
        assertThat(cmd(a, b, 10).hash()).isNotEqualTo(cmd(b, a, 10).hash());
    }

    @Test
    void idempotencyKeyFormat() {
        assertThat(Ledger.validKey("order-2026:11.25_a")).isEqualTo("order-2026:11.25_a");
        for (String bad : new String[] {null, "", "has space", "x".repeat(101), "semi;colon"}) {
            assertThatThrownBy(() -> Ledger.validKey(bad))
                    .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }
}
