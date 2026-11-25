package io.github.wynyga.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/** A request to reserve {@code amount} on the payer's account for the receiver, for {@code ttl}. */
public record HoldCommand(UUID payerAccountId, UUID receiverAccountId, long amount, String currency, Duration ttl) {
    public static final Duration MIN_TTL = Duration.ofSeconds(1);
    public static final Duration MAX_TTL = Duration.ofDays(30);
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    public HoldCommand {
        // Same amount, currency and same-account rules as a transfer.
        TransferCommand t = new TransferCommand(TransferType.CAPTURE, payerAccountId, receiverAccountId, amount,
                currency);
        currency = t.currency();
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new LedgerException(ErrorCode.HOLD_EXPIRY_INVALID, "a hold must expire in 1 second to 30 days");
        }
    }

    /** SHA-256 of the canonical request, for idempotent replays. */
    public String hash() {
        String canonical = "HOLD|" + payerAccountId + "|" + receiverAccountId + "|" + amount + "|" + currency + "|"
                + ttl.toSeconds();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
