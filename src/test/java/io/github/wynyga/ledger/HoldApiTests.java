package io.github.wynyga.ledger;

import static io.github.wynyga.ledger.ApiClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.wynyga.ledger.ApiClient.Res;
import io.github.wynyga.ledger.ApiClient.Session;
import io.github.wynyga.ledger.application.HoldService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Holds over HTTP: authorise, capture (partial), release, expiry, and who may do which. */
class HoldApiTests extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    HoldService holdService;

    ApiClient api;
    Session admin;
    Session alice;
    Session shop;
    String wallet;
    String till;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = api.admin();
        alice = api.newUser();
        shop = api.newUser();
        wallet = api.open(alice, "IDR");
        till = api.open(shop, "IDR");
        assertThat(api.deposit(admin, wallet, 100_000).status()).isEqualTo(201);
    }

    @Test
    void authorizeThenPartialCapture() {
        Res hold = authorize(alice, key(), 60_000, null);
        assertThat(hold.status()).isEqualTo(201);
        assertThat(hold.str("status")).isEqualTo("AUTHORIZED");

        Res acc = api.get("/api/v1/accounts/" + wallet, alice.token());
        assertThat(acc.num("balance")).isEqualTo(100_000);
        assertThat(acc.num("held")).isEqualTo(60_000);
        assertThat(acc.num("available")).isEqualTo(40_000);

        // The held money cannot be spent meanwhile.
        String other = api.open(alice, "IDR");
        assertThat(api.transfer(alice, key(), wallet, other, 50_000, "IDR").str("code")).isEqualTo("INSUFFICIENT_FUNDS");

        Res captured = capture(shop, hold.str("id"), 45_000L);
        assertThat(captured.status()).isEqualTo(200);
        assertThat(captured.str("status")).isEqualTo("CAPTURED");
        assertThat(captured.num("capturedAmount")).isEqualTo(45_000);

        acc = api.get("/api/v1/accounts/" + wallet, alice.token());
        assertThat(acc.num("balance")).isEqualTo(55_000);
        assertThat(acc.num("held")).isZero();
        assertThat(api.balance(shop, till)).isEqualTo(45_000);

        Res transfer = api.get("/api/v1/transfers/" + captured.str("captureTransferId"), alice.token());
        assertThat(transfer.str("type")).isEqualTo("CAPTURE");
        assertThat(transfer.num("amount")).isEqualTo(45_000);

        Res again = capture(shop, hold.str("id"), 45_000L);
        assertThat(again.status()).as("same capture again is a replay").isEqualTo(200);
        assertThat(again.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(capture(shop, hold.str("id"), 10_000L).str("code")).isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(api.balance(shop, till)).isEqualTo(45_000);

        assertThat(api.get("/api/v1/reconciliation", admin.token()).body()).containsEntry("clean", true);
    }

    @Test
    void fullCaptureWithoutABodyAndReplayOfAuthorize() {
        String k = key();
        Res first = authorize(alice, k, 25_000, null);
        Res repeat = authorize(alice, k, 25_000, null);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.str("id")).isEqualTo(first.str("id"));
        assertThat(authorize(alice, k, 26_000, null).str("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Res captured = api.post("/api/v1/holds/" + first.str("id") + "/capture", shop.token(), null, null);
        assertThat(captured.status()).isEqualTo(200);
        assertThat(captured.num("capturedAmount")).isEqualTo(25_000);
        assertThat(api.balance(alice, wallet)).isEqualTo(75_000);
    }

    @Test
    void releaseByEitherSide() {
        Res byPayer = authorize(alice, key(), 10_000, null);
        Res byShop = authorize(alice, key(), 20_000, null);

        assertThat(release(alice, byPayer.str("id")).str("status")).isEqualTo("RELEASED");
        assertThat(release(shop, byShop.str("id")).str("status")).isEqualTo("RELEASED");
        assertThat(release(shop, byShop.str("id")).headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(capture(shop, byShop.str("id"), null).str("code")).isEqualTo("HOLD_NOT_ACTIVE");

        Res acc = api.get("/api/v1/accounts/" + wallet, alice.token());
        assertThat(acc.num("held")).isZero();
        assertThat(acc.num("available")).isEqualTo(100_000);
    }

    @Test
    void whoMayDoWhat() {
        Res hold = authorize(alice, key(), 10_000, null);
        String id = hold.str("id");
        Session eve = api.newUser();

        assertThat(capture(alice, id, null).str("code")).as("the payer cannot capture").isEqualTo("HOLD_NOT_FOUND");
        assertThat(capture(eve, id, null).status()).isEqualTo(404);
        assertThat(release(eve, id).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/holds/" + id, eve.token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/holds/" + id, shop.token()).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/holds/" + id, admin.token()).status()).isEqualTo(200);

        Res stolen = authorize(shop, key(), 10_000, null);
        assertThat(stolen.str("code")).as("only the owner can reserve on an account").isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void aHoldThatRunsOutCannotBeCapturedAndIsReleased() throws Exception {
        Res hold = authorize(alice, key(), 30_000, 1L);
        Thread.sleep(1_200);

        Res late = capture(shop, hold.str("id"), null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.str("code")).isEqualTo("HOLD_EXPIRED");

        holdService.expireDue(500); // what the scheduler does every 30 s
        assertThat(api.get("/api/v1/holds/" + hold.str("id"), alice.token()).str("status")).isEqualTo("EXPIRED");
        assertThat(api.get("/api/v1/accounts/" + wallet, alice.token()).num("available")).isEqualTo(100_000);
    }

    @Test
    void validation() {
        assertThat(authorize(alice, key(), 100_001, null).str("code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(authorize(alice, key(), 10, 31L * 24 * 3600).str("code")).isEqualTo("HOLD_EXPIRY_INVALID");
        assertThat(authorize(alice, null, 10, null).str("code")).isEqualTo("IDEMPOTENCY_KEY_MISSING");
        Res hold = authorize(alice, key(), 10_000, null);
        assertThat(capture(shop, hold.str("id"), 10_001L).str("code")).isEqualTo("CAPTURE_EXCEEDS_HOLD");
        assertThat(api.get("/api/v1/holds/" + UUID.randomUUID(), alice.token()).str("code"))
                .isEqualTo("HOLD_NOT_FOUND");
    }

    // --- helpers

    Res authorize(Session caller, String key, long amount, Long ttlSeconds) {
        Map<String, Object> body = new HashMap<>(Map.of("fromAccountId", wallet, "toAccountId", till,
                "amount", amount, "currency", "IDR"));
        if (ttlSeconds != null) {
            body.put("expiresInSeconds", ttlSeconds);
        }
        return api.post("/api/v1/holds", caller.token(), key, body);
    }

    Res capture(Session caller, String holdId, Long amount) {
        Object body = amount == null ? Map.of() : Map.of("amount", amount);
        return api.post("/api/v1/holds/" + holdId + "/capture", caller.token(), null, body);
    }

    Res release(Session caller, String holdId) {
        return api.post("/api/v1/holds/" + holdId + "/release", caller.token(), null, Map.of());
    }
}
