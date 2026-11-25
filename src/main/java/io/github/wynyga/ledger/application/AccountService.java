package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.Currencies;
import io.github.wynyga.ledger.domain.Entry;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Use cases: open an account, read it, read its statement. */
public class AccountService {
    public static final int MAX_STATEMENT = 200;

    private final AccountStore accounts;
    private final Clock clock;

    public AccountService(AccountStore accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    public Account open(String currency) {
        String c = Currencies.normalize(currency);
        if (accounts.fundingAccountId(c).isEmpty()) {
            throw new LedgerException(ErrorCode.CURRENCY_NOT_SUPPORTED, "currency " + c + " is not supported");
        }
        Account account = Account.open(UUID.randomUUID(), c, clock.instant());
        accounts.insert(account);
        return account;
    }

    public Account get(UUID id) {
        return accounts.find(id).orElseThrow(() -> notFound(id));
    }

    public List<Entry> statement(UUID id, int limit) {
        get(id);
        return accounts.statement(id, Math.clamp(limit, 1, MAX_STATEMENT));
    }

    static LedgerException notFound(UUID id) {
        return new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "account " + id + " not found");
    }
}
