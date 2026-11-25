package io.github.wynyga.ledger.application;

/** Use case: check that every stored balance matches its entries and the ledger sums to zero. */
public class ReconciliationService {
    private final ReconciliationQuery query;

    public ReconciliationService(ReconciliationQuery query) {
        this.query = query;
    }

    public ReconciliationReport run() {
        return query.run();
    }
}
