package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.User;
import java.time.Duration;
import java.time.Instant;

/** Port: signs short-lived access tokens. */
public interface TokenIssuer {

    record AccessToken(String value, Instant issuedAt, Instant expiresAt) {
        public long expiresInSeconds() {
            return Duration.between(issuedAt, expiresAt).toSeconds();
        }
    }

    AccessToken issue(User user);
}
