package io.github.wynyga.ledger.domain;

public enum HoldStatus {
    /** Money is reserved on the payer's account. The only status that can change. */
    AUTHORIZED,
    /** Settled by a CAPTURE transfer; any uncaptured remainder went back to the payer. */
    CAPTURED,
    /** Cancelled by the payer or the receiver. */
    RELEASED,
    /** Not captured in time; released by the sweeper. */
    EXPIRED
}
