package io.github.wynyga.ledger;

import static io.github.wynyga.ledger.ApiClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.wynyga.ledger.ApiClient.Res;
import io.github.wynyga.ledger.ApiClient.Session;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Who may do what: authentication, ownership, admin-only operations, and per-user idempotency keys. */
class SecurityTests extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JwtEncoder encoder;

    ApiClient api;
    Session admin;
    Session alice;
    Session bob;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = api.admin();
        alice = api.newUser();
        bob = api.newUser();
    }

    // --- authentication

    @Test
    void requestsWithoutATokenAreRefused() {
        Res r = api.get("/api/v1/accounts/" + UUID.randomUUID(), null);

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.str("code")).isEqualTo("UNAUTHENTICATED");
        assertThat(r.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void badTokensAreRefused() {
        String good = alice.token();
        String tampered = good.substring(0, good.length() - 4) + (good.endsWith("AAAA") ? "BBBB" : "AAAA");
        String expired = sign(alice.userId(), "ledger-service", Instant.now().minus(10, ChronoUnit.MINUTES));
        String foreign = sign(alice.userId(), "someone-else", Instant.now().plus(10, ChronoUnit.MINUTES));

        for (String token : new String[] {"not-a-jwt", tampered, expired, foreign}) {
            Res r = api.get("/api/v1/auth/me", token);
            assertThat(r.status()).as(token).isEqualTo(401);
            assertThat(r.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        }
        assertThat(api.get("/api/v1/auth/me", good).status()).isEqualTo(200);
    }

    @Test
    void healthIsPublicOtherActuatorEndpointsAreAdminOnly() {
        // Tests run with metrics export off, so /actuator/prometheus does not exist here; /info has the same rule.
        assertThat(api.get("/actuator/health", null).status()).isEqualTo(200);
        assertThat(api.get("/actuator/info", null).status()).isEqualTo(401);
        assertThat(api.get("/actuator/info", alice.token()).status()).isEqualTo(403);
        assertThat(api.get("/actuator/info", admin.token()).status()).isEqualTo(200);
    }

    // --- registration and sign-in

    @Test
    void tokenResponseAndMe() {
        String name = "carol" + UUID.randomUUID().toString().substring(0, 8);
        api.post("/api/v1/auth/register", null, null, Map.of("username", name, "password", PASSWORD));

        Res t = api.post("/api/v1/auth/token", null, null, Map.of("username", name.toUpperCase(), "password",
                PASSWORD));
        assertThat(t.status()).isEqualTo(200);
        assertThat(t.body()).containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 900);
        assertThat(t.headers().getCacheControl()).isEqualTo("no-store");

        Res me = api.get("/api/v1/auth/me", t.str("accessToken"));
        assertThat(me.body()).containsEntry("username", name).containsEntry("admin", false);
        assertThat(api.get("/api/v1/auth/me", admin.token()).body()).containsEntry("admin", true);
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() {
        String name = api.get("/api/v1/auth/me", alice.token()).str("username");
        Res wrong = api.post("/api/v1/auth/token", null, null, Map.of("username", name, "password", "wrong-password"));
        Res unknown = api.post("/api/v1/auth/token", null, null, Map.of("username", "nobody-" + UUID.randomUUID()
                .toString().substring(0, 8), "password", "wrong-password"));

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
        assertThat(wrong.str("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void registrationRules() {
        String name = "dave" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(register(name, PASSWORD).status()).isEqualTo(201);
        assertThat(register(name.toUpperCase(), PASSWORD))
                .satisfies(r -> assertThat(r.status()).isEqualTo(409))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("USERNAME_TAKEN"));
        assertThat(register("x", PASSWORD).str("code")).isEqualTo("USERNAME_INVALID");
        assertThat(register("has space", PASSWORD).str("code")).isEqualTo("USERNAME_INVALID");
        assertThat(register("erin" + UUID.randomUUID().toString().substring(0, 8), "short").str("code"))
                .isEqualTo("PASSWORD_INVALID");
        assertThat(register("frank" + UUID.randomUUID().toString().substring(0, 8), "é".repeat(40)).str("code"))
                .as("80 bytes is over the BCrypt limit").isEqualTo("PASSWORD_INVALID");
        assertThat(register("admin", PASSWORD).str("code")).isEqualTo("USERNAME_TAKEN");
    }

    // --- ownership

    @Test
    void othersCannotSeeOrSpendMyAccount() {
        String mine = api.open(alice, "IDR");
        api.deposit(admin, mine, 1_000);
        String bobs = api.open(bob, "IDR");

        assertThat(api.get("/api/v1/accounts/" + mine, bob.token()).str("code")).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(api.get("/api/v1/accounts/" + mine + "/entries", bob.token()).status()).isEqualTo(404);
        assertThat(api.transfer(bob, key(), mine, bobs, 500, "IDR"))
                .satisfies(r -> assertThat(r.status()).isEqualTo(404))
                .satisfies(r -> assertThat(r.str("code")).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThat(api.transfer(admin, key(), mine, bobs, 500, "IDR").status())
                .as("admins cannot move customer money either").isEqualTo(404);

        assertThat(api.balance(alice, mine)).isEqualTo(1_000);
        assertThat(api.get("/api/v1/accounts/" + mine, admin.token()).status()).isEqualTo(200);
    }

    @Test
    void anyoneCanBePaidAndBothSidesSeeTheTransfer() {
        String mine = api.open(alice, "IDR");
        api.deposit(admin, mine, 1_000);
        String bobs = api.open(bob, "IDR");
        Session eve = api.newUser();

        Res t = api.transfer(alice, key(), mine, bobs, 250, "IDR");
        assertThat(t.status()).isEqualTo(201);
        assertThat(api.balance(bob, bobs)).isEqualTo(250);

        String path = "/api/v1/transfers/" + t.str("id");
        assertThat(api.get(path, alice.token()).status()).isEqualTo(200);
        assertThat(api.get(path, bob.token()).status()).isEqualTo(200);
        assertThat(api.get(path, admin.token()).status()).isEqualTo(200);
        assertThat(api.get(path, eve.token()).str("code")).isEqualTo("TRANSFER_NOT_FOUND");
    }

    @Test
    void onlyAdminsDepositAndReconcile() {
        String mine = api.open(alice, "IDR");

        Res dep = api.post("/api/v1/deposits", alice.token(), key(), Map.of("accountId", mine, "amount", 1_000_000));
        assertThat(dep.status()).isEqualTo(403);
        assertThat(dep.str("code")).isEqualTo("FORBIDDEN");
        assertThat(api.get("/api/v1/reconciliation", alice.token()).status()).isEqualTo(403);
        assertThat(api.balance(alice, mine)).isZero();
    }

    @Test
    void idempotencyKeysBelongToTheirUser() {
        String a = api.open(alice, "IDR");
        String b = api.open(bob, "IDR");
        api.deposit(admin, a, 1_000);
        api.deposit(admin, b, 1_000);
        String shared = key();

        Res fromAlice = api.transfer(alice, shared, a, b, 100, "IDR");
        Res fromBob = api.transfer(bob, shared, b, a, 300, "IDR");

        assertThat(fromAlice.status()).isEqualTo(201);
        assertThat(fromBob.status()).as("same key, other user: a new transfer, not a conflict").isEqualTo(201);
        assertThat(fromBob.str("id")).isNotEqualTo(fromAlice.str("id"));
        assertThat(api.balance(alice, a)).isEqualTo(1_200);
    }

    // --- helpers

    Res register(String username, String password) {
        return api.post("/api/v1/auth/register", null, null, Map.of("username", username, "password", password));
    }

    String sign(String subject, String issuer, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(expiresAt.minus(15, ChronoUnit.MINUTES))
                .expiresAt(expiresAt)
                .claim("scope", "ledger:user")
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
