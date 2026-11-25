package io.github.wynyga.ledger.application;

import java.util.Objects;
import java.util.UUID;

/** Who is making the request, as established by the web adapter from a verified token. */
public record Caller(UUID userId, boolean admin) {

    public Caller {
        Objects.requireNonNull(userId);
    }
}
