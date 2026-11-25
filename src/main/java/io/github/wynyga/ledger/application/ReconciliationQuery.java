package io.github.wynyga.ledger.application;

/** Port: recomputes balances from the entries. */
public interface ReconciliationQuery {

    ReconciliationReport run();
}
