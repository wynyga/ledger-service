package io.github.wynyga.ledger.config;

import io.github.wynyga.ledger.application.AccountService;
import io.github.wynyga.ledger.application.AccountStore;
import io.github.wynyga.ledger.application.AuthService;
import io.github.wynyga.ledger.application.HoldService;
import io.github.wynyga.ledger.application.HoldStore;
import io.github.wynyga.ledger.application.Outbox;
import io.github.wynyga.ledger.application.PasswordHasher;
import io.github.wynyga.ledger.application.ReconciliationQuery;
import io.github.wynyga.ledger.application.ReconciliationService;
import io.github.wynyga.ledger.application.TokenIssuer;
import io.github.wynyga.ledger.application.TransactionRunner;
import io.github.wynyga.ledger.application.TransferService;
import io.github.wynyga.ledger.application.TransferStore;
import io.github.wynyga.ledger.application.UserStore;
import io.github.wynyga.ledger.domain.LedgerException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: the use cases are plain classes, wired to the adapters here. */
@Configuration(proxyBeanMethods = false)
class LedgerConfig {
    private static final Logger log = LoggerFactory.getLogger(LedgerConfig.class);

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
    TransferService transferService(AccountStore accounts, TransferStore transfers, Outbox outbox,
            TransactionRunner tx, Clock clock) {
        return new TransferService(accounts, transfers, outbox, tx, clock);
    }

    @Bean
    HoldService holdService(AccountStore accounts, HoldStore holds, TransferStore transfers, Outbox outbox,
            TransactionRunner tx, Clock clock) {
        return new HoldService(accounts, holds, transfers, outbox, tx, clock);
    }

    @Bean
    ReconciliationService reconciliationService(ReconciliationQuery query) {
        return new ReconciliationService(query);
    }

    @Bean
    AuthService authService(UserStore users, PasswordHasher hasher, TokenIssuer tokens, Clock clock) {
        return new AuthService(users, hasher, tokens, clock);
    }

    /** Creates the first admin from ADMIN_USERNAME / ADMIN_PASSWORD. Without a password, nothing is created. */
    @Bean
    ApplicationRunner adminBootstrap(AuthService auth, @Value("${ledger.admin.username:admin}") String username,
            @Value("${ledger.admin.password:}") String password) {
        return args -> {
            if (password.isBlank()) {
                return;
            }
            try {
                if (auth.ensureAdmin(username, password)) {
                    log.info("created admin user '{}'", username);
                }
            } catch (LedgerException e) {
                // Fail fast, but say which setting is wrong and where it comes from.
                throw new IllegalStateException("ADMIN_USERNAME / ADMIN_PASSWORD (from .env or the environment) is"
                        + " not valid: " + e.getMessage(), e);
            }
        };
    }
}
