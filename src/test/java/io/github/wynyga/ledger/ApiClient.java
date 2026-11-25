package io.github.wynyga.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Small HTTP client for the integration tests. Never throws on 4xx/5xx: tests assert on the status. */
final class ApiClient {
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

    /** A signed-in user. */
    record Session(String userId, String token) {
    }

    private final RestClient http;

    ApiClient(int port) {
        this.http = RestClient.create("http://localhost:" + port);
    }

    Session newUser() {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Res reg = post("/api/v1/auth/register", null, null, Map.of("username", name, "password",
                IntegrationTest.PASSWORD));
        assertThat(reg.status()).isEqualTo(201);
        return new Session(reg.str("id"), token(name, IntegrationTest.PASSWORD));
    }

    Session admin() {
        String token = token(IntegrationTest.ADMIN, IntegrationTest.ADMIN_PASSWORD);
        return new Session(get("/api/v1/auth/me", token).str("id"), token);
    }

    String token(String username, String password) {
        Res r = post("/api/v1/auth/token", null, null, Map.of("username", username, "password", password));
        assertThat(r.status()).isEqualTo(200);
        return r.str("accessToken");
    }

    String open(Session owner, String currency) {
        Res r = post("/api/v1/accounts", owner.token(), null, Map.of("currency", currency));
        assertThat(r.status()).isEqualTo(201);
        return r.str("id");
    }

    Res deposit(Session admin, String account, long amount) {
        return post("/api/v1/deposits", admin.token(), key(), Map.of("accountId", account, "amount", amount));
    }

    long balance(Session owner, String account) {
        Res r = get("/api/v1/accounts/" + account, owner.token());
        assertThat(r.status()).isEqualTo(200);
        return r.num("balance");
    }

    Res transfer(Session caller, String key, String from, String to, long amount, String currency) {
        return post("/api/v1/transfers", caller.token(), key,
                Map.of("fromAccountId", from, "toAccountId", to, "amount", amount, "currency", currency));
    }

    List<Map<String, Object>> entries(Session owner, String account) {
        return http.get().uri("/api/v1/accounts/{id}/entries", account)
                .headers(h -> h.setBearerAuth(owner.token()))
                .retrieve().body(JSON_LIST);
    }

    Res post(String path, String token, String key, Object body) {
        var spec = http.post().uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(), res.getHeaders(), body(res)));
    }

    Res get(String path, String token) {
        var spec = http.get().uri(path);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(), res.getHeaders(), body(res)));
    }

    private static Map<String, Object> body(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        MediaType type = res.getHeaders().getContentType();
        if (type == null || !type.isCompatibleWith(MediaType.APPLICATION_JSON)
                && !type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)) {
            return Map.of();
        }
        Map<String, Object> m = res.bodyTo(JSON);
        return m == null ? Map.of() : m;
    }

    static String key() {
        return "test-" + UUID.randomUUID();
    }
}
