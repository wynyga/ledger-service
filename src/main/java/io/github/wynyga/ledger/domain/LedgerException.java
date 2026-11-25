package io.github.wynyga.ledger.domain;

/** A business rule refused the request. Carries a stable {@link ErrorCode}. */
public class LedgerException extends RuntimeException {
    private final ErrorCode code;

    public LedgerException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
