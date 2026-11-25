package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** The posting rules. Pure: the caller loads (and locks) the accounts and stores the result. */
public final class Ledger {
    /** Upper bound for one transfer, in minor units (Rp1,000,000,000,000). */
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private Ledger() {
    }

    public static String validKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key must be 1-100 letters, digits or . _ : -");
        }
        return key;
    }

    /**
     * Applies the command to the two accounts and returns the transfer with its two entries. Either both balances
     * change or neither does.
     */
    public static Transfer post(TransferCommand cmd, UUID requestedBy, String idempotencyKey, Account debit,
            Account credit, UUID transferId, Instant now) {
        if (!debit.id().equals(cmd.debitAccountId()) || !credit.id().equals(cmd.creditAccountId())) {
            throw new IllegalArgumentException("accounts do not match the command");
        }
        if (!debit.currency().equals(cmd.currency()) || !credit.currency().equals(cmd.currency())) {
            throw new LedgerException(ErrorCode.CURRENCY_MISMATCH, "both accounts must use " + cmd.currency());
        }
        // Check both sides before changing either.
        long debitAfter = debit.balanceAfterDebit(cmd.amount());
        long creditAfter = credit.balanceAfterCredit(cmd.amount());
        debit.apply(debitAfter);
        credit.apply(creditAfter);

        List<Entry> entries = List.of(
                new Entry(transferId, debit.id(), Direction.DEBIT, cmd.amount(), debitAfter, now),
                new Entry(transferId, credit.id(), Direction.CREDIT, cmd.amount(), creditAfter, now));
        return new Transfer(transferId, requestedBy, validKey(idempotencyKey), cmd.hash(), cmd.type(), debit.id(),
                credit.id(), cmd.amount(), cmd.currency(), now, entries);
    }
}
