# Ledger service

A double-entry ledger with a REST API: the core of an e-wallet or a bank's internal transfers, built with Spring
Boot 4, Java 21 and PostgreSQL.

It focuses on the parts of moving money that are easy to get wrong:

- **Balances always add up.** Every transfer writes one debit and one credit entry of the same amount, so the whole
  ledger sums to zero. `GET /api/v1/reconciliation` recomputes every balance from the entries and reports any
  difference.
- **A retried request never pays twice.** Every request that moves money carries an `Idempotency-Key`. Sending it again
  returns the original transfer (`200`, `Idempotent-Replayed: true`); reusing the key for a different request is a
  `409`. This also holds when the same key arrives on many threads at the same moment.
- **No overdraft under concurrency.** Both accounts are locked with `SELECT ... FOR UPDATE` in a fixed order, so
  parallel transfers queue instead of racing, and opposite transfers (A→B and B→A) cannot deadlock.
- **History is append-only.** Database triggers refuse any `UPDATE` or `DELETE` on transfers and entries.

The design is in [docs/DESIGN.md](docs/DESIGN.md).

## Architecture

```
domain/                  Account, Transfer, Entry, posting rules        plain Java
application/             use cases + ports (AccountStore, TransferStore, TransactionRunner, ...)   plain Java
adapter/in/web/          REST controllers, DTOs, RFC 9457 problem details
adapter/out/persistence/ JPA entities, Spring Data repositories, JDBC reconciliation
config/                  composition root
```

`ArchitectureTest` (ArchUnit) fails the build if `domain` or `application` imports Spring, Jakarta or Hibernate, or
if the web and persistence adapters depend on each other.

## Run

Needs JDK 21 or newer and Docker. Maven comes with the wrapper.

```bash
docker compose up -d          # PostgreSQL on localhost:5440
./mvnw spring-boot:run        # http://localhost:8080   (Windows: .\mvnw.cmd spring-boot:run)
```

Or without compose, with a throwaway database started by Testcontainers:

```bash
./mvnw spring-boot:test-run
```

## Try it

```bash
# open two accounts
curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"currency":"IDR"}'
curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"currency":"IDR"}'

# put money in the first one (A and B are the ids returned above)
curl -s -X POST localhost:8080/api/v1/deposits -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-001' -d '{"accountId":"A","amount":100000}'

# transfer; run it twice and the second answer is a replay, not a second payment
curl -si -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: trf-001' -d '{"fromAccountId":"A","toAccountId":"B","amount":30000,"currency":"IDR"}'

curl -s localhost:8080/api/v1/accounts/A/entries
curl -s localhost:8080/api/v1/reconciliation
```

Amounts are integers in minor units (`1` = Rp1). IDR and USD are supported: each has a system funding account that
deposits come from.

| Method | Path | |
|---|---|---|
| POST | `/api/v1/accounts` | open an account |
| GET | `/api/v1/accounts/{id}` | balance |
| GET | `/api/v1/accounts/{id}/entries?limit=50` | statement, newest first |
| POST | `/api/v1/deposits` | money in (needs `Idempotency-Key`) |
| POST | `/api/v1/transfers` | move money (needs `Idempotency-Key`) |
| GET | `/api/v1/transfers/{id}` | one transfer with its entries |
| GET | `/api/v1/reconciliation` | check the books |
| GET | `/actuator/prometheus` | metrics |

Errors are `application/problem+json` with a stable `code`, for example `INSUFFICIENT_FUNDS` (422),
`IDEMPOTENCY_KEY_REUSED` (409) or `ACCOUNT_NOT_FOUND` (404).

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

## Next

Spring Security with JWT and account ownership, a transactional outbox to Kafka, a k6 load test with published
numbers, and authorise/capture holds. See the last section of the design.
