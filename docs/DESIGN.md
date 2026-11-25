# Ledger service: design

A small double-entry ledger with a REST API, the core of an e-wallet or a bank's internal transfer system. The point
is the hard parts of money movement, not CRUD: balances that always add up, a retried request that never pays twice,
and parallel transfers that never overdraw an account.

## Principles

1. **Double entry.** Every movement of money is one transfer with exactly two entries: a debit on one account and a
   credit of the same amount on another. The sum of all entries in the ledger is always zero.
2. **The balance is derived and checked.** Each account stores its balance for fast reads, and each entry stores the
   balance after it. A reconciliation check recomputes every balance from the entries and reports any difference.
3. **Exactly once per idempotency key.** Every request that moves money carries an `Idempotency-Key`. Repeating the
   request returns the original result; reusing the key for a different request is refused.
4. **No overdraft under concurrency.** Both accounts of a transfer are locked (`SELECT ... FOR UPDATE`) in a fixed
   order (by id), so parallel transfers queue instead of racing, and two opposite transfers cannot deadlock.
5. **Integers for money.** Amounts are `long` minor units (IDR has no fractional unit in practice, so 1 = Rp1). There
   are no floating-point numbers anywhere.
6. **Least privilege.** A user moves money only out of accounts they own; deposits and reconciliation need the
   admin scope. Someone else's account answers "not found", never "forbidden", so ids cannot be probed.
7. **Clean architecture.** The domain and the use cases are plain Java with no Spring, JPA or servlet imports. A
   build-time test (ArchUnit) enforces this.

## Model

| Concept | Fields | Notes |
|---|---|---|
| Account | id (UUID), currency, balance, allow_negative, status, created_at | `allow_negative` is only set on system accounts |
| Transfer | id, idempotency_key (unique), request_hash, type, debit_account, credit_account, amount, currency, created_at | type is `TRANSFER` or `DEPOSIT` |
| Entry | id, transfer_id, account_id, direction (`DEBIT`/`CREDIT`), amount, balance_after, created_at | append-only |

A **deposit** is an ordinary transfer from the system *funding* account of that currency (seeded by the migration,
allowed to go negative) to the customer account. That keeps every movement double-entry: the funding account's
negative balance is exactly the money that entered the ledger from outside.

Rules a transfer must pass, in the domain:

- amount > 0 and at most 1,000,000,000,000 minor units;
- the two accounts differ, both are `ACTIVE`, and both use the transfer's currency;
- the debit account's balance does not go below zero unless `allow_negative`.

## Posting a transfer

```
request + Idempotency-Key
  │
  ├─ key already used? ── same request hash → return the stored transfer (200)
  │                    └─ different hash    → 409 Conflict
  │
  └─ BEGIN
       lock account with the smaller id  (FOR UPDATE)
       lock account with the larger id   (FOR UPDATE)
       domain: validate, debit, credit
       INSERT transfer (unique idempotency_key)
       INSERT 2 entries with balance_after
       UPDATE both balances
     COMMIT → 201 Created
```

Two requests with the same new key can arrive at the same moment. Both pass the first check, one commits, and the
other hits the unique constraint on `idempotency_key`; it is rolled back and answered from the stored transfer, as a
replay. The request hash is SHA-256 of the canonical request (type, accounts, amount, currency).

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/accounts` | Open an account `{ "currency": "IDR" }` |
| GET | `/api/v1/accounts/{id}` | Balance and status |
| GET | `/api/v1/accounts/{id}/entries?limit=50` | Statement, newest first |
| POST | `/api/v1/deposits` | `{ accountId, amount }` + `Idempotency-Key` |
| POST | `/api/v1/transfers` | `{ fromAccountId, toAccountId, amount, currency }` + `Idempotency-Key` |
| GET | `/api/v1/transfers/{id}` | One transfer with its entries |
| GET | `/api/v1/reconciliation` | Sum of all entries and any account whose balance differs from its entries |

Errors are RFC 9457 problem details (`application/problem+json`) with a stable `code`: `ACCOUNT_NOT_FOUND` (404),
`INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `SAME_ACCOUNT`, `ACCOUNT_INACTIVE` (422), `IDEMPOTENCY_KEY_REUSED` (409),
`VALIDATION_FAILED` and `IDEMPOTENCY_KEY_MISSING` (400).

## Security

Stateless bearer tokens, using Spring Security's own OAuth2 resource server support (no extra JWT library).

| Piece | Choice |
|---|---|
| Sign up / sign in | `POST /api/v1/auth/register`, `POST /api/v1/auth/token` (JSON username + password) |
| Passwords | BCrypt through the delegating encoder (`{bcrypt}...`), 10 characters minimum, 72 bytes maximum (the BCrypt input limit) |
| Token | JWT, HS256, 15 minutes; `sub` = user id, `scope` = `ledger:user` or `ledger:user ledger:admin`, `iss`, `iat`, `exp`, `jti` |
| Verification | signature, algorithm, expiry (60 s skew) and issuer; failures answer 401 with `WWW-Authenticate: Bearer error="invalid_token"` |
| Key | `JWT_SECRET`, base64, at least 32 bytes; when unset a random key is generated per start (development only) |
| First admin | created at startup from `ADMIN_USERNAME` / `ADMIN_PASSWORD` |

Rules, enforced in the use cases (the URL rules in the filter chain are a second lock, not the only one):

| Action | Who |
|---|---|
| open an account | any user; it belongs to them |
| read an account or statement | its owner, or an admin |
| transfer | the owner of the source account; any existing account may receive |
| read a transfer | the owners of either side, or an admin |
| deposit, reconciliation, actuator (except health) | admin |

An admin cannot move money out of a customer account. Login gives the same answer, after the same amount of work,
for an unknown user and a wrong password. Idempotency keys are unique **per requester** (`unique nulls not distinct
(requested_by, idempotency_key)`), so one user's key can never replay, block or reveal another user's transfer.

CSRF protection is off on purpose: no cookie or session authenticates a request, so a cross-site form has no
credential to ride on. Sessions are `STATELESS`.

Not done yet: refresh tokens, revocation (a deny list of `jti`), rate limiting of the token endpoint, and moving to
RS256 with a JWKS endpoint when a second service has to verify tokens.

## Layout

```
io.github.wynyga.ledger
  domain/        Account, Money rules, Transfer, Entry, LedgerException  (plain Java)
  application/   use cases (OpenAccount, PostTransfer, GetStatement, Reconcile) and ports
  adapter/in/web/        controllers, request/response records, problem-detail handler
  adapter/out/persistence/  JPA entities, Spring Data repositories, port implementations
  adapter/out/security/  BCrypt hashing, JWT signing
  config/        wiring of use cases to adapters (composition root)
```

The use cases need a transaction but must not import Spring, so they call a `TransactionRunner` port; the persistence
adapter implements it with `TransactionTemplate`.

## Tests

- **Domain unit tests**: posting, every rule, overflow guard. No Spring, run in milliseconds.
- **Integration tests on a real PostgreSQL** (Testcontainers): the HTTP flow, idempotent replay, key reuse.
- **Concurrency tests**: 
  - an account holding 100 receives 50 parallel transfers of 10 out: exactly 10 succeed, the balance ends at 0;
  - 400 random transfers in both directions between a few accounts on 16 threads: no deadlock error, no negative
    balance, reconciliation is clean and the total is unchanged.
- **Security tests**: missing, malformed, tampered, expired and foreign-issuer tokens; ownership of accounts and
  transfers; admin-only deposits and reconciliation; per-user idempotency keys; registration and login rules.
- **Architecture test** (ArchUnit): `domain` and `application` do not depend on Spring, Jakarta or the adapters.

## Later phases

1. **Outbox**: an `outbox` row written in the same transaction as the transfer, relayed to Kafka by a scheduled
   publisher, so a `TransferPosted` event is never lost and never sent for a rolled-back transfer.
2. **Load test**: k6 script and published numbers (transfers per second, p99 latency, zero reconciliation difference).
3. **Holds**: authorise-then-capture (reserve funds, then settle or release), as card payments do.
