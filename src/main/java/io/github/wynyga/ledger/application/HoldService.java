package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.Hold;
import io.github.wynyga.ledger.domain.HoldCommand;
import io.github.wynyga.ledger.domain.HoldStatus;
import io.github.wynyga.ledger.domain.Ledger;
import io.github.wynyga.ledger.domain.LedgerException;
import io.github.wynyga.ledger.domain.Transfer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Use cases for holds: the payer authorises, the receiver captures (all or part), either side releases, and the
 * sweeper expires what nobody settled. Lock order everywhere: accounts (by id), then the hold.
 */
public class HoldService {

    /** @param replayed true when the call repeated one that already succeeded and nothing changed */
    public record HoldResult(Hold hold, boolean replayed) {
    }

    private final AccountStore accounts;
    private final HoldStore holds;
    private final TransferStore transfers;
    private final Outbox outbox;
    private final TransactionRunner tx;
    private final Clock clock;

    public HoldService(AccountStore accounts, HoldStore holds, TransferStore transfers, Outbox outbox,
            TransactionRunner tx, Clock clock) {
        this.accounts = accounts;
        this.holds = holds;
        this.transfers = transfers;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /** Only the owner of the payer account may reserve money on it. Idempotent per caller and key. */
    public HoldResult authorize(Caller caller, String key, UUID payer, UUID receiver, long amount, String currency,
            Duration ttl) {
        Ledger.validKey(key);
        HoldCommand cmd = new HoldCommand(payer, receiver, amount, currency, ttl);
        var seen = holds.findByIdempotencyKey(caller.userId(), key);
        if (seen.isPresent()) {
            return replay(seen.get(), cmd.hash());
        }
        try {
            return new HoldResult(tx.inTransaction(() -> authorizeLocked(caller, key, cmd)), false);
        } catch (IdempotencyKeyTakenException e) {
            return replay(holds.findByIdempotencyKey(caller.userId(), key).orElseThrow(() -> e), cmd.hash());
        }
    }

    /**
     * The receiver settles the hold for {@code amount} (null = the full amount). Repeating a successful capture
     * with the same amount returns it unchanged.
     */
    public HoldResult capture(Caller caller, UUID holdId, Long amount) {
        Hold seen = visible(caller, holdId);
        if (!seen.receiverAccountId().equals(ownedSide(caller, seen))) {
            throw holdNotFound(holdId); // only the receiver captures
        }
        return tx.inTransaction(() -> {
            AccountLocks.Pair locked = AccountLocks.lock(accounts, seen.payerAccountId(), seen.receiverAccountId());
            Hold hold = holds.lock(holdId).orElseThrow(() -> holdNotFound(holdId));
            long captureAmount = amount == null ? hold.amount() : amount;
            if (hold.status() == HoldStatus.CAPTURED && hold.capturedAmount() == captureAmount) {
                return new HoldResult(hold, true);
            }
            Account payer = locked.get(hold.payerAccountId());
            Account receiver = locked.get(hold.receiverAccountId());
            Transfer t = hold.capture(captureAmount, caller.userId(), payer, receiver, UUID.randomUUID(),
                    clock.instant());
            transfers.insert(t);
            accounts.saveBalance(payer);
            accounts.saveBalance(receiver);
            holds.update(hold);
            outbox.transferPosted(t);
            return new HoldResult(hold, false);
        });
    }

    /** Either side may cancel. Releasing a hold that is already released returns it unchanged. */
    public HoldResult release(Caller caller, UUID holdId) {
        Hold seen = visible(caller, holdId);
        if (ownedSide(caller, seen) == null) {
            throw holdNotFound(holdId);
        }
        return tx.inTransaction(() -> {
            Account payer = accounts.lock(seen.payerAccountId())
                    .orElseThrow(() -> AccountService.notFound(seen.payerAccountId()));
            Hold hold = holds.lock(holdId).orElseThrow(() -> holdNotFound(holdId));
            if (hold.status() == HoldStatus.RELEASED) {
                return new HoldResult(hold, true);
            }
            hold.release(payer, clock.instant());
            accounts.saveBalance(payer);
            holds.update(hold);
            return new HoldResult(hold, false);
        });
    }

    /** Visible to the owners of either side and to admins; anyone else gets "not found". */
    public Hold get(Caller caller, UUID holdId) {
        return visible(caller, holdId);
    }

    /** Releases holds whose time ran out. Each hold in its own short transaction. Returns how many expired. */
    public int expireDue(int limit) {
        int expired = 0;
        for (UUID id : holds.dueForExpiry(clock.instant(), limit)) {
            Boolean done = tx.inTransaction(() -> {
                Hold peek = holds.find(id).orElse(null);
                if (peek == null) {
                    return false;
                }
                Account payer = accounts.lock(peek.payerAccountId()).orElseThrow();
                Hold hold = holds.lock(id).orElseThrow();
                if (!hold.expire(payer, clock.instant())) {
                    return false; // captured or released meanwhile
                }
                accounts.saveBalance(payer);
                holds.update(hold);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    private Hold authorizeLocked(Caller caller, String key, HoldCommand cmd) {
        AccountLocks.Pair locked = AccountLocks.lock(accounts, cmd.payerAccountId(), cmd.receiverAccountId());
        Account payer = locked.get(cmd.payerAccountId());
        Account receiver = locked.get(cmd.receiverAccountId());
        if (!payer.isOwnedBy(caller.userId())) {
            throw AccountService.notFound(payer.id());
        }
        Hold hold = Hold.authorize(cmd, caller.userId(), key, payer, receiver, UUID.randomUUID(), clock.instant());
        holds.insert(hold);
        accounts.saveBalance(payer);
        return hold;
    }

    private Hold visible(Caller caller, UUID holdId) {
        Hold h = holds.find(holdId).orElseThrow(() -> holdNotFound(holdId));
        if (caller.admin() || ownedSide(caller, h) != null) {
            return h;
        }
        throw holdNotFound(holdId);
    }

    /** The receiver's account id if the caller owns it, else the payer's if they own that, else null. */
    private UUID ownedSide(Caller caller, Hold h) {
        if (owns(caller, h.receiverAccountId())) {
            return h.receiverAccountId();
        }
        if (owns(caller, h.payerAccountId())) {
            return h.payerAccountId();
        }
        return null;
    }

    private boolean owns(Caller caller, UUID accountId) {
        return accounts.find(accountId).map(a -> a.isOwnedBy(caller.userId())).orElse(false);
    }

    private static HoldResult replay(Hold stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was already used for a different request");
        }
        return new HoldResult(stored, true);
    }

    static LedgerException holdNotFound(UUID id) {
        return new LedgerException(ErrorCode.HOLD_NOT_FOUND, "hold " + id + " not found");
    }
}
