package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.application.ReconciliationReport;
import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.Entry;
import io.github.wynyga.ledger.domain.Transfer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies. The domain types never leave the adapter as JSON directly. */
final class Dtos {
    private Dtos() {
    }

    record OpenAccountRequest(@NotBlank String currency) {
    }

    record DepositRequest(@NotNull UUID accountId, @Positive long amount) {
    }

    record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId, @Positive long amount,
            @NotBlank String currency) {
    }

    /** available = balance - held: what transfers and new holds may use. */
    record AccountResponse(UUID id, UUID ownerId, String currency, long balance, long held, long available,
            String status, Instant createdAt) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.id(), a.ownerId(), a.currency(), a.balance(), a.held(), a.available(),
                    a.status().name(), a.createdAt());
        }
    }

    record EntryResponse(UUID transferId, UUID accountId, String direction, long amount, long balanceAfter,
            Instant createdAt) {
        static EntryResponse of(Entry e) {
            return new EntryResponse(e.transferId(), e.accountId(), e.direction().name(), e.amount(),
                    e.balanceAfter(), e.createdAt());
        }
    }

    record TransferResponse(UUID id, String type, String idempotencyKey, UUID debitAccountId,
            UUID creditAccountId, long amount, String currency, Instant createdAt, List<EntryResponse> entries) {
        static TransferResponse of(Transfer t) {
            return new TransferResponse(t.id(), t.type().name(), t.idempotencyKey(), t.debitAccountId(),
                    t.creditAccountId(), t.amount(), t.currency(), t.createdAt(),
                    t.entries().stream().map(EntryResponse::of).toList());
        }
    }

    record ReconciliationResponse(boolean clean, long accounts, long entries,
            List<ReconciliationReport.CurrencyTotal> totals, List<ReconciliationReport.Mismatch> mismatches,
            List<ReconciliationReport.Mismatch> heldMismatches) {
        static ReconciliationResponse of(ReconciliationReport r) {
            return new ReconciliationResponse(r.clean(), r.accounts(), r.entries(), r.totals(), r.mismatches(),
                    r.heldMismatches());
        }
    }
}
