package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Transfer;

/** Port: records events for other systems. Must write inside the caller's transaction, so both commit or neither. */
public interface Outbox {

    void transferPosted(Transfer transfer);
}
