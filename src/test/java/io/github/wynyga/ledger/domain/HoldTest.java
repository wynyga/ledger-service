package io.github.wynyga.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldTest {
    static final Instant NOW = LedgerTest.NOW;
    static final UUID PAYER = UUID.randomUUID();
    static final UUID MERCHANT = UUID.randomUUID();

    final Account payer = new Account(UUID.randomUUID(), PAYER, "IDR", 100_000, 0, false, AccountStatus.ACTIVE, NOW);
    final Account shop = new Account(UUID.randomUUID(), MERCHANT, "IDR", 0, 0, false, AccountStatus.ACTIVE, NOW);

    Hold authorize(long amount) {
        HoldCommand cmd = new HoldCommand(payer.id(), shop.id(), amount, "IDR", Duration.ofHours(1));
        return Hold.authorize(cmd, PAYER, "h-" + UUID.randomUUID(), payer, shop, UUID.randomUUID(), NOW);
    }

    static ErrorCode code(Throwable e) {
        return ((LedgerException) e).code();
    }

    @Test
    void authorizingReservesMoneyWithoutMovingIt() {
        Hold h = authorize(60_000);

        assertThat(h.status()).isEqualTo(HoldStatus.AUTHORIZED);
        assertThat(h.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(payer.balance()).isEqualTo(100_000);
        assertThat(payer.held()).isEqualTo(60_000);
        assertThat(payer.available()).isEqualTo(40_000);
    }

    @Test
    void heldMoneyCannotBeSpentOrHeldTwice() {
        authorize(60_000);

        assertThatThrownBy(() -> authorize(40_001)).extracting(HoldTest::code).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        var spend = new TransferCommand(TransferType.TRANSFER, payer.id(), shop.id(), 40_001, "IDR");
        assertThatThrownBy(() -> Ledger.post(spend, PAYER, "t", payer, shop, UUID.randomUUID(), NOW))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(payer.held()).isEqualTo(60_000);

        Ledger.post(new TransferCommand(TransferType.TRANSFER, payer.id(), shop.id(), 40_000, "IDR"), PAYER, "t2",
                payer, shop, UUID.randomUUID(), NOW);
        assertThat(payer.available()).isZero();
    }

    @Test
    void partialCaptureMovesThePartAndFreesTheRest() {
        Hold h = authorize(60_000);
        UUID transferId = UUID.randomUUID();

        Transfer t = h.capture(45_000, MERCHANT, payer, shop, transferId, NOW.plusSeconds(10));

        assertThat(t.type()).isEqualTo(TransferType.CAPTURE);
        assertThat(t.amount()).isEqualTo(45_000);
        assertThat(t.idempotencyKey()).isEqualTo("capture:" + h.id());
        assertThat(payer.balance()).isEqualTo(55_000);
        assertThat(payer.held()).isZero();
        assertThat(shop.balance()).isEqualTo(45_000);
        assertThat(h.status()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(h.capturedAmount()).isEqualTo(45_000);
        assertThat(h.captureTransferId()).isEqualTo(transferId);
    }

    @Test
    void captureRules() {
        Hold h = authorize(60_000);

        assertThatThrownBy(() -> h.capture(60_001, MERCHANT, payer, shop, UUID.randomUUID(), NOW))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.CAPTURE_EXCEEDS_HOLD);
        assertThatThrownBy(() -> h.capture(10, MERCHANT, payer, shop, UUID.randomUUID(), NOW.plus(Duration.ofHours(1))))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(payer.held()).as("refused captures change nothing").isEqualTo(60_000);

        h.capture(60_000, MERCHANT, payer, shop, UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> h.capture(1, MERCHANT, payer, shop, UUID.randomUUID(), NOW))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
        assertThatThrownBy(() -> h.release(payer, NOW)).extracting(HoldTest::code)
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    void aRefusedCapturePutsTheReservationBack() {
        Hold h = authorize(60_000);
        Account frozenShop = new Account(shop.id(), MERCHANT, "IDR", 0, 0, false, AccountStatus.FROZEN, NOW);

        assertThatThrownBy(() -> h.capture(10_000, MERCHANT, payer, frozenShop, UUID.randomUUID(), NOW))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
        assertThat(payer.held()).isEqualTo(60_000);
        assertThat(payer.balance()).isEqualTo(100_000);
        assertThat(h.status()).isEqualTo(HoldStatus.AUTHORIZED);
    }

    @Test
    void releaseAndExpiryGiveTheMoneyBack() {
        Hold released = authorize(30_000);
        Hold expiring = authorize(20_000);
        assertThat(payer.available()).isEqualTo(50_000);

        released.release(payer, NOW);
        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(payer.available()).isEqualTo(80_000);

        assertThat(expiring.expire(payer, NOW.plus(Duration.ofMinutes(59)))).as("not due yet").isFalse();
        assertThat(expiring.expire(payer, NOW.plus(Duration.ofHours(1)))).isTrue();
        assertThat(expiring.status()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(payer.held()).isZero();
        assertThat(payer.balance()).isEqualTo(100_000);
        assertThat(expiring.expire(payer, NOW.plus(Duration.ofHours(2)))).as("only once").isFalse();
    }

    @Test
    void commandRules() {
        assertThatThrownBy(() -> new HoldCommand(payer.id(), shop.id(), 10, "IDR", Duration.ofDays(31)))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.HOLD_EXPIRY_INVALID);
        assertThatThrownBy(() -> new HoldCommand(payer.id(), payer.id(), 10, "IDR", null))
                .extracting(HoldTest::code).isEqualTo(ErrorCode.SAME_ACCOUNT);
        assertThat(new HoldCommand(payer.id(), shop.id(), 10, "idr", null).ttl()).isEqualTo(HoldCommand.DEFAULT_TTL);
        assertThat(new HoldCommand(payer.id(), shop.id(), 10, "IDR", null).hash())
                .isNotEqualTo(new HoldCommand(payer.id(), shop.id(), 10, "IDR", Duration.ofDays(1)).hash());
    }
}
