package io.github.wynyga.ledger.adapter.out.security;

import io.github.wynyga.ledger.application.PasswordHasher;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Delegating encoder: stores {@code {bcrypt}...}, so the algorithm can be upgraded later without a migration. */
@Component
class SpringPasswordHasher implements PasswordHasher {
    private static final int BCRYPT_MAX_BYTES = 72;

    private final PasswordEncoder encoder;

    SpringPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hash) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            // Never stored (registration refuses it), so it cannot be right; still spend the time of a check.
            encoder.matches("", hash);
            return false;
        }
        return encoder.matches(rawPassword, hash);
    }
}
