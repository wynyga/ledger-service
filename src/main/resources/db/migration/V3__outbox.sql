-- Transactional outbox: written in the same transaction as the transfer, relayed to Kafka afterwards.

create table outbox (
    id           bigint generated always as identity primary key,
    event_id     uuid         not null constraint uq_outbox_event_id unique,
    event_type   varchar(64)  not null,
    -- Kafka message key: the transfer id.
    aggregate_id uuid         not null,
    payload      jsonb        not null,
    created_at   timestamptz  not null,
    published_at timestamptz,
    attempts     int          not null default 0,
    last_error   varchar(500)
);

-- The relay only ever reads unpublished rows in id order; this index stays as small as the backlog.
create index ix_outbox_pending on outbox (id) where published_at is null;
