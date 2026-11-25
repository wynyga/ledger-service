package io.github.wynyga.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Money reserved on the payer's account for a receiver. While {@link HoldStatus#AUTHORIZED} the amount counts in
 * the payer's {@code held}, so it cannot be spent twice; the balance itself does not change until capture.
 */
public final class Hold {
    private final UUID id;
    private final UUID requestedBy;
    private final String idempotencyKey;
    private final String requestHash;
    private final UUID payerAccountId;
    private final UUID receiverAccountId;
    private final long amount;
    private final String currency;
    private final Instant createdAt;
    private final Instant expiresAt;
    private HoldStatus status;
    private Instant settledAt;
    private Long capturedAmount;
    private UUID captureTransferId;

    public Hold(UUID id, UUID requestedBy, String idempotencyKey, String requestHash, UUID payerAccountId,
            UUID receiverAccountId, long amount, String currency, HoldStatus status, Instant createdAt,
            Instant expiresAt, Instant settledAt, Long capturedAmount, UUID captureTransferId) {
        this.id = Objects.requireNonNull(id);
        this.requestedBy = requestedBy;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.payerAccountId = Objects.requireNonNull(payerAccountId);
        this.receiverAccountId = Objects.requireNonNull(receiverAccountId);
        this.amount = amount;
        this.currency = currency;
        this.status = Objects.requireNonNull(status);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.settledAt = settledAt;
        this.capturedAmount = capturedAmount;
        this.captureTransferId = captureTransferId;
    }

    /** Reserves the amount on the payer's account. Refused when the spendable balance is too low. */
    public static Hold authorize(HoldCommand cmd, UUID requestedBy, String idempotencyKey, Account payer,
            Account receiver, UUID holdId, Instant now) {
        if (!payer.id().equals(cmd.payerAccountId()) || !receiver.id().equals(cmd.receiverAccountId())) {
            throw new IllegalArgumentException("accounts do not match the command");
        }
        if (!payer.currency().equals(cmd.currency()) || !receiver.currency().equals(cmd.currency())) {
            throw new LedgerException(ErrorCode.CURRENCY_MISMATCH, "both accounts must use " + cmd.currency());
        }
        receiver.ensureActive();
        payer.reserve(cmd.amount());
        return new Hold(holdId, requestedBy, Ledger.validKey(idempotencyKey), cmd.hash(), payer.id(), receiver.id(),
                cmd.amount(), cmd.currency(), HoldStatus.AUTHORIZED, now, now.plus(cmd.ttl()), null, null, null);
    }

    /**
     * Settles the hold: the whole reservation is released and {@code captureAmount} (at most the held amount) moves
     * to the receiver as a CAPTURE transfer. The rest stays with the payer.
     */
    public Transfer capture(long captureAmount, UUID capturedBy, Account payer, Account receiver, UUID transferId,
            Instant now) {
        ensureAuthorized(now);
        if (captureAmount <= 0 || captureAmount > amount) {
            throw new LedgerException(ErrorCode.CAPTURE_EXCEEDS_HOLD,
                    "capture must be between 1 and the held amount " + amount);
        }
        TransferCommand cmd = new TransferCommand(TransferType.CAPTURE, payerAccountId, receiverAccountId,
                captureAmount, currency);
        // The reserved money is what pays for the capture, so free it first; put it back if posting is refused.
        payer.releaseReserve(amount);
        Transfer t;
        try {
            t = Ledger.post(cmd, capturedBy, "capture:" + id, payer, receiver, transferId, now);
        } catch (RuntimeException e) {
            payer.restoreReserve(amount);
            throw e;
        }
        status = HoldStatus.CAPTURED;
        capturedAmount = captureAmount;
        captureTransferId = t.id();
        settledAt = now;
        return t;
    }

    /** Cancels the reservation. Allowed on a frozen account: giving money back is always safe. */
    public void release(Account payer, Instant now) {
        if (status != HoldStatus.AUTHORIZED) {
            throw notActive();
        }
        payer.releaseReserve(amount);
        status = HoldStatus.RELEASED;
        settledAt = now;
    }

    /** Releases the reservation of a hold whose time ran out. Returns false when it is not due (or not active). */
    public boolean expire(Account payer, Instant now) {
        if (status != HoldStatus.AUTHORIZED || now.isBefore(expiresAt)) {
            return false;
        }
        payer.releaseReserve(amount);
        status = HoldStatus.EXPIRED;
        settledAt = now;
        return true;
    }

    private void ensureAuthorized(Instant now) {
        if (status != HoldStatus.AUTHORIZED) {
            throw notActive();
        }
        if (!now.isBefore(expiresAt)) {
            throw new LedgerException(ErrorCode.HOLD_EXPIRED, "hold " + id + " expired at " + expiresAt);
        }
    }

    private LedgerException notActive() {
        return new LedgerException(ErrorCode.HOLD_NOT_ACTIVE, "hold " + id + " is " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public UUID payerAccountId() {
        return payerAccountId;
    }

    public UUID receiverAccountId() {
        return receiverAccountId;
    }

    public long amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public HoldStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant settledAt() {
        return settledAt;
    }

    public Long capturedAmount() {
        return capturedAmount;
    }

    public UUID captureTransferId() {
        return captureTransferId;
    }
}
