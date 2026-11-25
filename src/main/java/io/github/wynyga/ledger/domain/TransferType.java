package io.github.wynyga.ledger.domain;

public enum TransferType {
    /** Between two customer accounts. */
    TRANSFER,
    /** From the system funding account into a customer account: money entering the ledger. */
    DEPOSIT
}
