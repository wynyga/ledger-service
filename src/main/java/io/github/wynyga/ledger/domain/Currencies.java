package io.github.wynyga.ledger.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** ISO 4217 style codes. Which currencies are usable is decided by the funding accounts that exist. */
public final class Currencies {
    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private Currencies() {
    }

    public static String normalize(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(c).matches()) {
            throw new LedgerException(ErrorCode.CURRENCY_NOT_SUPPORTED, "currency must be a 3-letter code");
        }
        return c;
    }
}
