package io.github.wynyga.ledger.application;

import java.util.List;
import java.util.UUID;

/**
 * Result of a reconciliation run.
 *
 * @param totals     sum of all signed entries per currency; zero in a healthy ledger
 * @param mismatches accounts whose stored balance differs from the sum of their entries
 */
public record ReconciliationReport(long accounts, long entries, List<CurrencyTotal> totals,
        List<Mismatch> mismatches) {

    public ReconciliationReport {
        totals = List.copyOf(totals);
        mismatches = List.copyOf(mismatches);
    }

    public boolean clean() {
        return mismatches.isEmpty() && totals.stream().allMatch(t -> t.sum() == 0);
    }

    public record CurrencyTotal(String currency, long sum) {
    }

    public record Mismatch(UUID accountId, long storedBalance, long entriesBalance) {
    }
}
