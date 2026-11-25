package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringTransactionRunner implements TransactionRunner {
    private final TransactionTemplate template;

    SpringTransactionRunner(PlatformTransactionManager tm) {
        this.template = new TransactionTemplate(tm);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
