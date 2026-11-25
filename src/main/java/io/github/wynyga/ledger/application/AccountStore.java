package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.Entry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: where accounts and their entries live. */
public interface AccountStore {

    void insert(Account account);

    Optional<Account> find(UUID id);

    /** Loads the account and locks it until the surrounding transaction ends. Only call inside a transaction. */
    Optional<Account> lock(UUID id);

    /** Writes the account's balance. Only call inside the transaction that locked it. */
    void saveBalance(Account account);

    /** The system account that money enters from, or empty when the currency is not supported. */
    Optional<UUID> fundingAccountId(String currency);

    /** Newest first. */
    List<Entry> statement(UUID accountId, int limit);
}
