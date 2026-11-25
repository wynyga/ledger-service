package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.Ledger;
import io.github.wynyga.ledger.domain.LedgerException;
import io.github.wynyga.ledger.domain.Transfer;
import io.github.wynyga.ledger.domain.TransferCommand;
import io.github.wynyga.ledger.domain.TransferType;
import java.time.Clock;
import java.util.UUID;

/** Use cases that move money. Every call is idempotent per key. */
public class TransferService {

    /** @param replayed true when the key was seen before and the stored transfer is returned unchanged */
    public record PostResult(Transfer transfer, boolean replayed) {
    }

    private final AccountStore accounts;
    private final TransferStore transfers;
    private final TransactionRunner tx;
    private final Clock clock;

    public TransferService(AccountStore accounts, TransferStore transfers, TransactionRunner tx, Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.tx = tx;
        this.clock = clock;
    }

    public PostResult transfer(String key, UUID from, UUID to, long amount, String currency) {
        Ledger.validKey(key);
        return post(key, new TransferCommand(TransferType.TRANSFER, from, to, amount, currency));
    }

    /** Money entering the ledger: debits the funding account of the target account's currency. */
    public PostResult deposit(String key, UUID accountId, long amount) {
        Ledger.validKey(key);
        Account target = accounts.find(accountId).orElseThrow(() -> AccountService.notFound(accountId));
        UUID funding = accounts.fundingAccountId(target.currency())
                .orElseThrow(() -> new LedgerException(ErrorCode.CURRENCY_NOT_SUPPORTED,
                        "currency " + target.currency() + " is not supported"));
        return post(key, new TransferCommand(TransferType.DEPOSIT, funding, accountId, amount, target.currency()));
    }

    public Transfer get(UUID id) {
        return transfers.find(id).orElseThrow(
                () -> new LedgerException(ErrorCode.TRANSFER_NOT_FOUND, "transfer " + id + " not found"));
    }

    private PostResult post(String key, TransferCommand cmd) {
        String hash = cmd.hash();
        var seen = transfers.findByIdempotencyKey(key);
        if (seen.isPresent()) {
            return replay(seen.get(), hash);
        }
        try {
            Transfer posted = tx.inTransaction(() -> postLocked(key, cmd));
            return new PostResult(posted, false);
        } catch (IdempotencyKeyTakenException e) {
            // A concurrent request with the same key committed first; answer as a replay of it.
            return replay(transfers.findByIdempotencyKey(key).orElseThrow(() -> e), hash);
        }
    }

    private Transfer postLocked(String key, TransferCommand cmd) {
        // Lock in a fixed order so that A->B and B->A running together queue instead of deadlocking.
        UUID first = cmd.debitAccountId().compareTo(cmd.creditAccountId()) < 0
                ? cmd.debitAccountId() : cmd.creditAccountId();
        UUID second = first.equals(cmd.debitAccountId()) ? cmd.creditAccountId() : cmd.debitAccountId();
        Account a = accounts.lock(first).orElseThrow(() -> AccountService.notFound(first));
        Account b = accounts.lock(second).orElseThrow(() -> AccountService.notFound(second));
        Account debit = a.id().equals(cmd.debitAccountId()) ? a : b;
        Account credit = debit == a ? b : a;

        Transfer transfer = Ledger.post(cmd, key, debit, credit, UUID.randomUUID(), clock.instant());
        transfers.insert(transfer);
        accounts.saveBalance(debit);
        accounts.saveBalance(credit);
        return transfer;
    }

    private static PostResult replay(Transfer stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was already used for a different request");
        }
        return new PostResult(stored, true);
    }
}
