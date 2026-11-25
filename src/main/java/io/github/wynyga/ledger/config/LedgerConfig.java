package io.github.wynyga.ledger.config;

import io.github.wynyga.ledger.application.AccountService;
import io.github.wynyga.ledger.application.AccountStore;
import io.github.wynyga.ledger.application.ReconciliationQuery;
import io.github.wynyga.ledger.application.ReconciliationService;
import io.github.wynyga.ledger.application.TransactionRunner;
import io.github.wynyga.ledger.application.TransferService;
import io.github.wynyga.ledger.application.TransferStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: the use cases are plain classes, wired to the adapters here. */
@Configuration(proxyBeanMethods = false)
class LedgerConfig {

    /** Microseconds, the precision PostgreSQL stores, so a replay returns exactly the timestamps first returned. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    AccountService accountService(AccountStore accounts, Clock clock) {
        return new AccountService(accounts, clock);
    }

    @Bean
    TransferService transferService(AccountStore accounts, TransferStore transfers, TransactionRunner tx,
            Clock clock) {
        return new TransferService(accounts, transfers, tx, clock);
    }

    @Bean
    ReconciliationService reconciliationService(ReconciliationQuery query) {
        return new ReconciliationService(query);
    }
}
