-- Holds (authorise, then capture or release), as card payments work.

-- Money reserved by active holds. Spendable = balance - held.
alter table accounts add column held bigint not null default 0;
alter table accounts add constraint chk_accounts_held check (held >= 0);
alter table accounts drop constraint chk_accounts_balance;
alter table accounts add constraint chk_accounts_available check (allow_negative or balance - held >= 0);

create table holds (
    id                  uuid primary key,
    requested_by        uuid         not null references users (id),
    idempotency_key     varchar(100) not null,
    request_hash        varchar(64)  not null,
    debit_account_id    uuid         not null references accounts (id),
    credit_account_id   uuid         not null references accounts (id),
    amount              bigint       not null check (amount > 0),
    currency            varchar(3)   not null,
    status              varchar(16)  not null check (status in ('AUTHORIZED', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    created_at          timestamptz  not null,
    expires_at          timestamptz  not null,
    settled_at          timestamptz,
    captured_amount     bigint check (captured_amount > 0 and captured_amount <= amount),
    capture_transfer_id uuid references transfers (id),
    constraint uq_holds_requester_key unique (requested_by, idempotency_key),
    check (debit_account_id <> credit_account_id),
    check ((status = 'CAPTURED') = (capture_transfer_id is not null))
);

-- The expiry sweeper only looks at active holds.
create index ix_holds_expiring on holds (expires_at) where status = 'AUTHORIZED';
create index ix_holds_debit on holds (debit_account_id);
create index ix_holds_credit on holds (credit_account_id);
