package io.github.wynyga.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** The HTTP contract, end to end, on a real PostgreSQL. */
class LedgerApiTests extends IntegrationTest {
    static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() {
    };

    record Res(int status, HttpHeaders headers, Map<String, Object> body) {
        long num(String field) {
            return ((Number) body.get(field)).longValue();
        }

        String str(String field) {
            return String.valueOf(body.get(field));
        }
    }

    @Value("${local.server.port}")
    int port;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    void depositTransferAndStatement() {
        String a = open("IDR");
        String b = open("IDR");

        Res dep = post("/api/v1/deposits", key(), Map.of("accountId", a, "amount", 100_000));
        assertThat(dep.status()).isEqualTo(201);
        assertThat(dep.str("type")).isEqualTo("DEPOSIT");

        Res t = transfer(key(), a, b, 30_000, "IDR");
        assertThat(t.status()).isEqualTo(201);
        assertThat(t.headers().getLocation()).hasToString("/api/v1/transfers/" + t.str("id"));
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) t.body().get("entries");
        assertThat(entries).extracting(e -> e.get("direction")).containsExactly("DEBIT", "CREDIT");
        assertThat(entries).extracting(e -> ((Number) e.get("balanceAfter")).longValue()).containsExactly(70_000L,
                30_000L);

        assertThat(balance(a)).isEqualTo(70_000);
        assertThat(balance(b)).isEqualTo(30_000);

        var statement = http.get().uri("/api/v1/accounts/{id}/entries", a).retrieve().body(JSON_LIST);
        assertThat(statement).hasSize(2);
        assertThat(statement.get(0)).containsEntry("direction", "DEBIT").containsEntry("transferId", t.str("id"));
        assertThat(statement.get(1)).containsEntry("direction", "CREDIT").containsEntry("transferId", dep.str("id"));

        Res fetched = get("/api/v1/transfers/" + t.str("id"));
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(t.body());
    }

    @Test
    void repeatingAKeyReplaysInsteadOfPayingTwice() {
        String a = funded(1_000);
        String b = open("IDR");
        String k = key();

        Res first = transfer(k, a, b, 400, "IDR");
        Res again = transfer(k, a, b, 400, "IDR");

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(balance(a)).isEqualTo(600);
    }

    @Test
    void reusingAKeyForAnotherRequestIsAConflict() {
        String a = funded(1_000);
        String b = open("IDR");
        String k = key();
        transfer(k, a, b, 400, "IDR");

        Res other = transfer(k, a, b, 401, "IDR");

        assertThat(other.status()).isEqualTo(409);
        assertThat(other.str("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(a)).isEqualTo(600);
    }

    @Test
    void aRefusedTransferDoesNotUseUpItsKey() {
        String a = funded(100);
        String b = open("IDR");
        String k = key();

        Res refused = transfer(k, a, b, 500, "IDR");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.str("code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(refused.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(balance(a)).isEqualTo(100);

        post("/api/v1/deposits", key(), Map.of("accountId", a, "amount", 400));
        assertThat(transfer(k, a, b, 500, "IDR").status()).isEqualTo(201);
        assertThat(balance(a)).isZero();
    }

    @Test
    void errorsHaveStableCodes() {
        String idr = funded(1_000);
        String usd = open("USD");

        assertThat(transfer(null, idr, usd, 10, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(400))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("IDEMPOTENCY_KEY_MISSING"));
        assertThat(transfer("bad key!", idr, usd, 10, "IDR").str("code")).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(transfer(key(), idr, usd, 10, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(422))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("CURRENCY_MISMATCH"));
        assertThat(transfer(key(), idr, idr, 10, "IDR").str("code")).isEqualTo("SAME_ACCOUNT");
        assertThat(transfer(key(), idr, UUID.randomUUID().toString(), 10, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(404))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("ACCOUNT_NOT_FOUND"));

        Res zero = transfer(key(), idr, usd, 0, "IDR");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.str("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(zero.body().get("errors")).isEqualTo(Map.of("amount", "must be greater than 0"));

        Res eur = post("/api/v1/accounts", null, Map.of("currency", "EUR"));
        assertThat(eur.status()).isEqualTo(422);
        assertThat(eur.str("code")).isEqualTo("CURRENCY_NOT_SUPPORTED");

        assertThat(get("/api/v1/accounts/not-a-uuid").status()).isEqualTo(400);
        assertThat(get("/api/v1/transfers/" + UUID.randomUUID()).str("code")).isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(balance(idr)).isEqualTo(1_000);
    }

    @Test
    void reconciliationIsClean() {
        String a = funded(5_000);
        transfer(key(), a, open("IDR"), 1_234, "IDR");

        Res r = get("/api/v1/reconciliation");

        assertThat(r.body()).containsEntry("clean", true).containsEntry("mismatches", List.of());
    }

    // --- helpers

    String open(String currency) {
        Res r = post("/api/v1/accounts", null, Map.of("currency", currency));
        assertThat(r.status()).isEqualTo(201);
        return r.str("id");
    }

    String funded(long amount) {
        String id = open("IDR");
        assertThat(post("/api/v1/deposits", key(), Map.of("accountId", id, "amount", amount)).status())
                .isEqualTo(201);
        return id;
    }

    long balance(String account) {
        return get("/api/v1/accounts/" + account).num("balance");
    }

    Res transfer(String key, String from, String to, long amount, String currency) {
        return post("/api/v1/transfers", key,
                Map.of("fromAccountId", from, "toAccountId", to, "amount", amount, "currency", currency));
    }

    Res post(String path, String key, Object body) {
        var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(), res.getHeaders(), res.bodyTo(JSON)));
    }

    Res get(String path) {
        return http.get().uri(path)
                .exchange((req, res) -> new Res(res.getStatusCode().value(), res.getHeaders(), res.bodyTo(JSON)));
    }

    static String key() {
        return "test-" + UUID.randomUUID();
    }
}
