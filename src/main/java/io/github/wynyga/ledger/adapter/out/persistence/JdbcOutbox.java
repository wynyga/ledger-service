package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.Outbox;
import io.github.wynyga.ledger.application.OutboxMessage;
import io.github.wynyga.ledger.application.OutboxStore;
import io.github.wynyga.ledger.domain.Transfer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox table, written with plain JDBC on the same DataSource: inside a JPA transaction the JDBC statements use
 * the transaction's connection, so the row commits or rolls back with the transfer.
 */
@Component
class JdbcOutbox implements Outbox, OutboxStore {
    static final String TRANSFER_POSTED = "TransferPosted";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcOutbox(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void transferPosted(Transfer t) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId.toString());
        body.put("eventType", TRANSFER_POSTED);
        body.put("occurredAt", t.createdAt().toString());
        body.put("transferId", t.id().toString());
        body.put("transferType", t.type().name());
        body.put("debitAccountId", t.debitAccountId().toString());
        body.put("creditAccountId", t.creditAccountId().toString());
        body.put("amount", t.amount());
        body.put("currency", t.currency());
        body.put("requestedBy", t.requestedBy() == null ? null : t.requestedBy().toString());

        jdbc.sql("""
                insert into outbox (event_id, event_type, aggregate_id, payload, created_at)
                values (:eventId, :type, :aggregate, cast(:payload as jsonb), :createdAt)""")
                .param("eventId", eventId)
                .param("type", TRANSFER_POSTED)
                .param("aggregate", t.id())
                .param("payload", json.writeValueAsString(body))
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
    }

    @Override
    public List<OutboxMessage> lockPending(int limit) {
        return jdbc.sql("""
                select id, event_id, event_type, aggregate_id, payload::text, attempts
                from outbox
                where published_at is null
                order by id
                limit :limit
                for update skip locked""")
                .param("limit", limit)
                .query((rs, i) -> new OutboxMessage(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, UUID.class).toString(), rs.getString(5), rs.getInt(6)))
                .list();
    }

    @Override
    public void markPublished(long id, Instant at) {
        jdbc.sql("update outbox set published_at = :at where id = :id")
                .param("at", Timestamp.from(at))
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(long id, String error) {
        jdbc.sql("update outbox set attempts = attempts + 1, last_error = left(:error, 500) where id = :id")
                .param("error", error == null ? "" : error)
                .param("id", id)
                .update();
    }

    @Override
    public long pendingCount() {
        return jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
    }
}
