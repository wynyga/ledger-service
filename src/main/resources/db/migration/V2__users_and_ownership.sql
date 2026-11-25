-- Users, account ownership, and idempotency keys scoped per user.

create table users (
    id            uuid primary key,
    username      varchar(32)  not null constraint uq_users_username unique,
    password_hash varchar(100) not null,
    role          varchar(16)  not null check (role in ('USER', 'ADMIN')),
    created_at    timestamptz  not null
);

-- Null for system accounts (funding), which nobody owns.
alter table accounts add column owner_id uuid references users (id);
create index ix_accounts_owner on accounts (owner_id);

-- Who asked for the transfer. A key is unique per requester, so two users can never collide on, or probe, each
-- other's keys. Rows from before this migration have no requester; NULLS NOT DISTINCT keeps their keys unique too.
alter table transfers add column requested_by uuid references users (id);
alter table transfers drop constraint uq_transfers_idempotency_key;
alter table transfers add constraint uq_transfers_requester_key
    unique nulls not distinct (requested_by, idempotency_key);
