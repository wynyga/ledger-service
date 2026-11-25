package io.github.wynyga.ledger.domain;

public enum Role {
    /** Opens accounts, moves money out of their own accounts. */
    USER,
    /** Operations: records deposits and runs reconciliation. Cannot move money out of customer accounts. */
    ADMIN
}
