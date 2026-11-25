package io.github.wynyga.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** What a caller asks for: move {@code amount} from the debit account to the credit account. */
public record TransferCommand(TransferType type, UUID debitAccountId, UUID creditAccountId, long amount,
        String currency) {

    public TransferCommand {
        if (type == null || debitAccountId == null || creditAccountId == null) {
            throw new IllegalArgumentException("type and both accounts are required");
        }
        currency = Currencies.normalize(currency);
        if (amount <= 0 || amount > Ledger.MAX_AMOUNT) {
            throw new LedgerException(ErrorCode.INVALID_AMOUNT,
                    "amount must be between 1 and " + Ledger.MAX_AMOUNT + " minor units");
        }
        if (debitAccountId.equals(creditAccountId)) {
            throw new LedgerException(ErrorCode.SAME_ACCOUNT, "cannot transfer to the same account");
        }
    }

    /**
     * SHA-256 of the canonical request. A repeated idempotency key is a replay only when this hash matches the
     * stored one.
     */
    public String hash() {
        String canonical = type + "|" + debitAccountId + "|" + creditAccountId + "|" + amount + "|" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
