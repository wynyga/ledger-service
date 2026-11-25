package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.application.Caller;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Turns a verified token into the {@link Caller} the use cases understand. */
final class Callers {
    private Callers() {
    }

    static Caller from(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        boolean admin = scope != null && Arrays.asList(scope.split(" ")).contains("ledger:admin");
        return new Caller(UUID.fromString(jwt.getSubject()), admin);
    }
}
