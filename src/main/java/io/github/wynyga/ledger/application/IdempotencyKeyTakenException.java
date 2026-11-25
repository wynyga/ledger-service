package io.github.wynyga.ledger.application;

/** Raised by {@link TransferStore#insert} when a concurrent request committed the same key first. */
public class IdempotencyKeyTakenException extends RuntimeException {

    public IdempotencyKeyTakenException(String key, Throwable cause) {
        super("idempotency key already used: " + key, cause);
    }
}
