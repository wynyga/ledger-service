package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;

/** Use case: check that every stored balance matches its entries and the ledger sums to zero. Admins only. */
public class ReconciliationService {
    private final ReconciliationQuery query;

    public ReconciliationService(ReconciliationQuery query) {
        this.query = query;
    }

    public ReconciliationReport run(Caller caller) {
        if (!caller.admin()) {
            throw new LedgerException(ErrorCode.FORBIDDEN, "only an admin can run reconciliation");
        }
        return query.run();
    }
}
