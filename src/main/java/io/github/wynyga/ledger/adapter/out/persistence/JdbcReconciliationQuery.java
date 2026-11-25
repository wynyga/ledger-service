package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.ReconciliationQuery;
import io.github.wynyga.ledger.application.ReconciliationReport;
import io.github.wynyga.ledger.application.ReconciliationReport.CurrencyTotal;
import io.github.wynyga.ledger.application.ReconciliationReport.Mismatch;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL: aggregates over the whole ledger are clearer and cheaper than loading entities. */
@Component
class JdbcReconciliationQuery implements ReconciliationQuery {
    private static final String SIGNED = "case when e.direction = 'CREDIT' then e.amount else -e.amount end";

    private final JdbcClient jdbc;

    JdbcReconciliationQuery(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** One snapshot for all queries, so transfers committing meanwhile cannot produce a false mismatch. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        long accounts = jdbc.sql("select count(*) from accounts").query(Long.class).single();
        long entries = jdbc.sql("select count(*) from entries").query(Long.class).single();
        var totals = jdbc.sql("""
                select a.currency, coalesce(sum(%s), 0)
                from entries e join accounts a on a.id = e.account_id
                group by a.currency order by a.currency""".formatted(SIGNED))
                .query((rs, i) -> new CurrencyTotal(rs.getString(1), rs.getLong(2)))
                .list();
        var mismatches = jdbc.sql("""
                select a.id, a.balance, coalesce(sum(%1$s), 0)
                from accounts a left join entries e on e.account_id = a.id
                group by a.id, a.balance
                having a.balance <> coalesce(sum(%1$s), 0)""".formatted(SIGNED))
                .query((rs, i) -> new Mismatch(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)))
                .list();
        return new ReconciliationReport(accounts, entries, totals, mismatches);
    }
}
