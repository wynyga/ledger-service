package io.github.wynyga.ledger.application;

import java.util.List;
import java.util.UUID;

/**
 * Result of a reconciliation run.
 *
 * @param totals         sum of all signed entries per currency; zero in a healthy ledger
 * @param mismatches     accounts whose stored balance differs from the sum of their entries
 * @param heldMismatches accounts whose stored {@code held} differs from the sum of their active holds
 */
public record ReconciliationReport(long accounts, long entries, List<CurrencyTotal> totals,
        List<Mismatch> mismatches, List<Mismatch> heldMismatches) {

    public ReconciliationReport {
        totals = List.copyOf(totals);
        mismatches = List.copyOf(mismatches);
        heldMismatches = List.copyOf(heldMismatches);
    }

    public boolean clean() {
        return mismatches.isEmpty() && heldMismatches.isEmpty() && totals.stream().allMatch(t -> t.sum() == 0);
    }

    public record CurrencyTotal(String currency, long sum) {
    }

    /** @param stored what the account row says; computed what the entries (or holds) add up to */
    public record Mismatch(UUID accountId, long stored, long computed) {
    }
}
