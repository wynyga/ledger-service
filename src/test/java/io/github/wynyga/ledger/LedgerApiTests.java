package io.github.wynyga.ledger;

import static io.github.wynyga.ledger.ApiClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.wynyga.ledger.ApiClient.Res;
import io.github.wynyga.ledger.ApiClient.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

/** The money-moving HTTP contract, end to end, on a real PostgreSQL. Access rules are in {@link SecurityTests}. */
class LedgerApiTests extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    ApiClient api;
    Session admin;
    Session alice;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = api.admin();
        alice = api.newUser();
    }

    @Test
    void depositTransferAndStatement() {
        String a = api.open(alice, "IDR");
        String b = api.open(alice, "IDR");

        Res dep = api.deposit(admin, a, 100_000);
        assertThat(dep.status()).isEqualTo(201);
        assertThat(dep.str("type")).isEqualTo("DEPOSIT");

        Res t = api.transfer(alice, key(), a, b, 30_000, "IDR");
        assertThat(t.status()).isEqualTo(201);
        assertThat(t.headers().getLocation()).hasToString("/api/v1/transfers/" + t.str("id"));
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) t.body().get("entries");
        assertThat(entries).extracting(e -> e.get("direction")).containsExactly("DEBIT", "CREDIT");
        assertThat(entries).extracting(e -> ((Number) e.get("balanceAfter")).longValue())
                .containsExactly(70_000L, 30_000L);

        assertThat(api.balance(alice, a)).isEqualTo(70_000);
        assertThat(api.balance(alice, b)).isEqualTo(30_000);

        var statement = api.entries(alice, a);
        assertThat(statement).hasSize(2);
        assertThat(statement.get(0)).containsEntry("direction", "DEBIT").containsEntry("transferId", t.str("id"));
        assertThat(statement.get(1)).containsEntry("direction", "CREDIT").containsEntry("transferId", dep.str("id"));

        Res fetched = api.get("/api/v1/transfers/" + t.str("id"), alice.token());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(t.body());
    }

    @Test
    void repeatingAKeyReplaysInsteadOfPayingTwice() {
        String a = funded(1_000);
        String b = api.open(alice, "IDR");
        String k = key();

        Res first = api.transfer(alice, k, a, b, 400, "IDR");
        Res again = api.transfer(alice, k, a, b, 400, "IDR");

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(api.balance(alice, a)).isEqualTo(600);
    }

    @Test
    void reusingAKeyForAnotherRequestIsAConflict() {
        String a = funded(1_000);
        String b = api.open(alice, "IDR");
        String k = key();
        api.transfer(alice, k, a, b, 400, "IDR");

        Res other = api.transfer(alice, k, a, b, 401, "IDR");

        assertThat(other.status()).isEqualTo(409);
        assertThat(other.str("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(api.balance(alice, a)).isEqualTo(600);
    }

    @Test
    void aRefusedTransferDoesNotUseUpItsKey() {
        String a = funded(100);
        String b = api.open(alice, "IDR");
        String k = key();

        Res refused = api.transfer(alice, k, a, b, 500, "IDR");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.str("code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(refused.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(api.balance(alice, a)).isEqualTo(100);

        api.deposit(admin, a, 400);
        assertThat(api.transfer(alice, k, a, b, 500, "IDR").status()).isEqualTo(201);
        assertThat(api.balance(alice, a)).isZero();
    }

    @Test
    void errorsHaveStableCodes() {
        String idr = funded(1_000);
        String usd = api.open(alice, "USD");

        assertThat(api.transfer(alice, null, idr, usd, 10, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(400))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("IDEMPOTENCY_KEY_MISSING"));
        assertThat(api.transfer(alice, "bad key!", idr, usd, 10, "IDR").str("code"))
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(api.transfer(alice, key(), idr, usd, 10, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(422))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("CURRENCY_MISMATCH"));
        assertThat(api.transfer(alice, key(), idr, idr, 10, "IDR").str("code")).isEqualTo("SAME_ACCOUNT");
        assertThat(api.transfer(alice, key(), idr, UUID.randomUUID().toString(), 10, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(404))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("ACCOUNT_NOT_FOUND"));

        Res zero = api.transfer(alice, key(), idr, usd, 0, "IDR");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.str("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(zero.body().get("errors")).isEqualTo(Map.of("amount", "must be greater than 0"));

        Res eur = api.post("/api/v1/accounts", alice.token(), null, Map.of("currency", "EUR"));
        assertThat(eur.status()).isEqualTo(422);
        assertThat(eur.str("code")).isEqualTo("CURRENCY_NOT_SUPPORTED");

        assertThat(api.get("/api/v1/accounts/not-a-uuid", alice.token()).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/transfers/" + UUID.randomUUID(), alice.token()).str("code"))
                .isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(api.balance(alice, idr)).isEqualTo(1_000);
    }

    @Test
    void reconciliationIsClean() {
        String a = funded(5_000);
        api.transfer(alice, key(), a, api.open(alice, "IDR"), 1_234, "IDR");

        Res r = api.get("/api/v1/reconciliation", admin.token());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).containsEntry("clean", true).containsEntry("mismatches", List.of());
    }

    String funded(long amount) {
        String id = api.open(alice, "IDR");
        assertThat(api.deposit(admin, id, amount).status()).isEqualTo(201);
        return id;
    }
}
