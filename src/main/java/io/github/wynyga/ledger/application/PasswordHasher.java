package io.github.wynyga.ledger.application;

/** Port: one-way, salted, deliberately slow password hashing. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
