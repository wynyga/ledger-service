package io.github.wynyga.ledger.application;

import java.util.function.Supplier;

/** Port: runs work in one database transaction, rolled back if the work throws. */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
