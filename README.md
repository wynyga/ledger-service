# Ledger service

A double-entry ledger with a REST API: the core of an e-wallet or a bank's internal transfers, built with Spring
Boot 4, Spring Security (JWT), Java 21 and PostgreSQL.

It focuses on the parts of moving money that are easy to get wrong:

- **Balances always add up.** Every transfer writes one debit and one credit entry of the same amount, so the whole
  ledger sums to zero. `GET /api/v1/reconciliation` recomputes every balance from the entries and reports any
  difference.
- **A retried request never pays twice.** Every request that moves money carries an `Idempotency-Key`. Sending it again
  returns the original transfer (`200`, `Idempotent-Replayed: true`); reusing the key for a different request is a
  `409`. This also holds when the same key arrives on many threads at the same moment.
- **No overdraft under concurrency.** Both accounts are locked with `SELECT ... FOR UPDATE` in a fixed order, so
  parallel transfers queue instead of racing, and opposite transfers (A→B and B→A) cannot deadlock.
- **Only the owner spends.** JWT bearer tokens (Spring Security resource server, HS256). A user moves money only out
  of their own accounts; deposits and reconciliation need the admin scope. Other people's accounts answer `404`.
- **History is append-only.** Database triggers refuse any `UPDATE` or `DELETE` on transfers and entries.

The design is in [docs/DESIGN.md](docs/DESIGN.md).

## Architecture

```
domain/                  Account, Transfer, Entry, posting rules        plain Java
application/             use cases + ports (AccountStore, TransferStore, TransactionRunner, ...)   plain Java
adapter/in/web/          REST controllers, DTOs, security filter chain, RFC 9457 problem details
adapter/out/persistence/ JPA entities, Spring Data repositories, JDBC reconciliation
adapter/out/security/    BCrypt password hashing, JWT signing
config/                  composition root
```

`ArchitectureTest` (ArchUnit) fails the build if `domain` or `application` imports Spring, Jakarta or Hibernate, or
if the web and persistence adapters depend on each other.

## Run

Needs JDK 21 or newer and Docker. Maven comes with the wrapper.

```bash
docker compose up -d                       # PostgreSQL on localhost:5440
export JWT_SECRET=$(openssl rand -base64 32)
export ADMIN_PASSWORD='choose-a-long-one'  # creates the user "admin" at startup
./mvnw spring-boot:run                     # http://localhost:8080
```

On Windows PowerShell: `$env:ADMIN_PASSWORD = "choose-a-long-one"` and `.\mvnw.cmd spring-boot:run`. Without
`JWT_SECRET` a random key is used and tokens stop working when the app restarts.

Or without compose, with a throwaway database started by Testcontainers: `./mvnw spring-boot:test-run`.

## Try it

```bash
J='Content-Type: application/json'
curl -s -X POST localhost:8080/api/v1/auth/register -H "$J" -d '{"username":"alice","password":"correct horse battery"}'
ALICE=$(curl -s -X POST localhost:8080/api/v1/auth/token -H "$J" \
  -d '{"username":"alice","password":"correct horse battery"}' | jq -r .accessToken)
ADMIN=$(curl -s -X POST localhost:8080/api/v1/auth/token -H "$J" \
  -d '{"username":"admin","password":"choose-a-long-one"}' | jq -r .accessToken)

# alice opens two accounts (A and B are the returned ids)
curl -s -X POST localhost:8080/api/v1/accounts -H "$J" -H "Authorization: Bearer $ALICE" -d '{"currency":"IDR"}'

# the admin records money coming in
curl -s -X POST localhost:8080/api/v1/deposits -H "$J" -H "Authorization: Bearer $ADMIN" \
  -H 'Idempotency-Key: dep-001' -d '{"accountId":"A","amount":100000}'

# alice transfers; run it twice and the second answer is a replay, not a second payment
curl -si -X POST localhost:8080/api/v1/transfers -H "$J" -H "Authorization: Bearer $ALICE" \
  -H 'Idempotency-Key: trf-001' -d '{"fromAccountId":"A","toAccountId":"B","amount":30000,"currency":"IDR"}'

curl -s localhost:8080/api/v1/accounts/A/entries -H "Authorization: Bearer $ALICE"
curl -s localhost:8080/api/v1/reconciliation -H "Authorization: Bearer $ADMIN"
```

Amounts are integers in minor units (`1` = Rp1). IDR and USD are supported: each has a system funding account that
deposits come from.

| Method | Path | Who | |
|---|---|---|---|
| POST | `/api/v1/auth/register` | anyone | create a user |
| POST | `/api/v1/auth/token` | anyone | get a 15-minute access token |
| GET | `/api/v1/auth/me` | user | who the token belongs to |
| POST | `/api/v1/accounts` | user | open an account (owned by the caller) |
| GET | `/api/v1/accounts/{id}` | owner, admin | balance |
| GET | `/api/v1/accounts/{id}/entries?limit=50` | owner, admin | statement, newest first |
| POST | `/api/v1/transfers` | owner of the source | move money (needs `Idempotency-Key`) |
| GET | `/api/v1/transfers/{id}` | either side, admin | one transfer with its entries |
| POST | `/api/v1/deposits` | admin | money in (needs `Idempotency-Key`) |
| GET | `/api/v1/reconciliation` | admin | check the books |
| GET | `/actuator/health` | anyone | liveness |
| GET | `/actuator/prometheus` | admin | metrics |

Errors are `application/problem+json` with a stable `code`, for example `INSUFFICIENT_FUNDS` (422),
`IDEMPOTENCY_KEY_REUSED` (409), `ACCOUNT_NOT_FOUND` (404), `UNAUTHENTICATED` (401) or `FORBIDDEN` (403).

## Test

```bash
./mvnw test
```

Integration tests run against a real PostgreSQL 17 in Docker (Testcontainers). Besides the API contract they include:

| Test | Shows |
|---|---|
| 50 parallel withdrawals of 10 from an account holding 100 | exactly 10 succeed, the balance ends at 0 |
| 20 parallel requests with the same Idempotency-Key | one transfer, 19 replays, money moves once |
| 400 random transfers in both directions on 16 threads | no deadlock, no negative balance, total unchanged, reconciliation clean |

`SecurityTests` covers missing, malformed, tampered, expired and foreign-issuer tokens, account and transfer
ownership, admin-only operations, per-user idempotency keys, and the registration and login rules.

## Next

Refresh tokens and revocation, a transactional outbox to Kafka, a k6 load test with published
numbers, and authorise/capture holds. See the last section of the design.
