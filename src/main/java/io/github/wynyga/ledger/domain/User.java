package io.github.wynyga.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A user who can sign in. The password is only ever held as a hash. */
public record User(UUID id, String username, String passwordHash, Role role, Instant createdAt) {
    public static final int MIN_PASSWORD = 10;
    /** BCrypt reads at most 72 bytes; longer input would be silently cut (or refused), so refuse it here. */
    public static final int MAX_PASSWORD_BYTES = 72;

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");

    public User {
        Objects.requireNonNull(id);
        username = normalizeUsername(username);
        Objects.requireNonNull(passwordHash);
        Objects.requireNonNull(role);
        Objects.requireNonNull(createdAt);
    }

    /** Lower case, 3-32 characters of letters, digits, '.', '_' or '-', starting with a letter or digit. */
    public static String normalizeUsername(String username) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw new LedgerException(ErrorCode.USERNAME_INVALID,
                    "username must be 3-32 letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
        return u;
    }

    public static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new LedgerException(ErrorCode.PASSWORD_INVALID,
                    "password must be at least " + MIN_PASSWORD + " characters and at most "
                            + MAX_PASSWORD_BYTES + " bytes");
        }
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
