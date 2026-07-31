# LedgerGuard

LedgerGuard is a portfolio-quality transaction ledger and reconciliation
platform, built to demonstrate backend software engineering practices:
double-entry accounting, transactional correctness, and test-driven
development against a real database.

## Status: Phase 1 complete (Tasks 1–9); Phase 2, Tasks 10–12 implemented

This repository contains the project foundation (Task 1), the initial
database schema (Task 2), account creation (Task 3), deposits (Task 4),
transfers (Task 5), read-only account balance/transaction-history APIs
(Task 6), centralized error handling (Task 7), OpenAPI/Swagger
documentation (Task 8), continuous integration (Task 9), idempotency for
deposits and transfers (Task 10), a transactional outbox for durable
event persistence (Task 11), and publishing pending outbox events to
Kafka (Task 12). A Kafka consumer, settlement/reconciliation, and
authentication remain unimplemented — see `docs/TASKS.md`.

`POST /api/v1/accounts` creates a **customer USD wallet account**. Every
account created through this endpoint always opens with a **zero balance**
and the server-assigned `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` taxonomy —
a client cannot choose the account category, class, purpose, currency
(other than USD), initial balance, id, or creation timestamp.

`POST /api/v1/accounts/{id}/deposits` and `POST /api/v1/transfers` both
**require an `Idempotency-Key` request header** (1–128 characters,
`[A-Za-z0-9._:-]`). A retry with the same key and the same amount/currency/
account(s) replays the exact original response instead of creating a new
transaction; a retry with the same key but *different* request data —
including reusing a deposit's key against `/transfers` or vice versa —
returns `409 Conflict`. See "Idempotency" below.

`POST /api/v1/accounts/{id}/deposits` deposits USD into a customer wallet.
**Every deposit is an atomic, balanced double-entry ledger transaction**:
it DEBITs the internal `EXTERNAL_FUNDING` asset account and CREDITs the
destination wallet (a liability account), both for the same amount and
currency, both referencing one new ledger transaction row — and it
increases both accounts' materialized balances in the very same database
transaction as those two ledger-entry writes.

`POST /api/v1/transfers` moves USD between two customer wallets. **Every
transfer is also an atomic, balanced double-entry ledger transaction**: it
DEBITs the source customer liability (decreasing its balance) and CREDITs
the destination customer liability (increasing its balance), both for the
same amount and currency, both referencing one new ledger transaction row.
The *combined* balance of the two accounts is unchanged by a transfer — it
moves money internally, unlike a deposit. `EXTERNAL_FUNDING` is never
involved in a transfer. A transfer is rejected (422) if the source account
doesn't have enough balance to cover it.

Both accounts involved in a deposit or a transfer are row-locked in
PostgreSQL, in deterministic ascending account-id order (never
source-then-destination, never customer-then-funding) — this is what
prevents lost updates under concurrent deposits to the same account, lets
concurrent transfers from one source correctly allow only as many as the
balance can cover, and lets two opposite-direction transfers between the
same two accounts (A→B and B→A) complete concurrently without deadlocking.
Ledger rows remain immutable — deposit and transfer processing only ever
insert into `ledger_transaction`/`ledger_entry`, never update or delete
them, and the Task 2 immutability triggers are untouched.

`GET /api/v1/accounts/{id}/balance` returns an account's current
**materialized balance** — the same persisted number deposits and
transfers maintain — read directly, never recomputed and never cached.
`GET /api/v1/accounts/{id}/transactions` returns that account's own
transaction history, derived from the immutable `ledger_entry` records:
deposits appear as customer-wallet **credits**, outgoing transfers appear
as **debits**, incoming transfers appear as **credits**. History is
newest-first (`created_at DESC`, with the entry's own id as a deterministic
tie-breaker) and paginated (`page`/`size` query parameters, defaults
`page=0`/`size=20`, `size` capped at `100`). Both read endpoints are plain
database reads — no row locking, no ledger writes, no balance changes —
and both hide `SYSTEM` accounts (like `EXTERNAL_FUNDING`) exactly like the
write endpoints already do: a `SYSTEM` account id is indistinguishable
from a nonexistent one (404).

**Every error response, from every endpoint above, shares one JSON shape**
— `{timestamp, status, error, message, path}` — produced by a single
centralized `@RestControllerAdvice`. This covers request-validation
failures (missing/malformed fields, malformed JSON, unknown/protected
properties, malformed pagination), domain failures (account not found,
`SYSTEM` account access, unsupported currency, insufficient funds,
same-account transfer), and unexpected failures (a safe generic 500, with
the real exception logged server-side only). No internal detail is ever
returned to a client: no Java class or package names, no stack traces, no
SQL, no database constraint or table names.

**Every implemented endpoint, request/response schema, and the shared
error envelope are documented via OpenAPI**, generated directly from the
controllers and DTOs (not a hand-maintained spec file, so it can't drift
from the real API): the OpenAPI 3.1 JSON document is at `/v3/api-docs`,
and interactive Swagger UI is at `/swagger-ui/index.html`. No
authentication scheme is declared, because Phase 1 has none — declaring
one would falsely imply these endpoints are protected.

**Every push and pull request to `master` automatically runs the complete
verification suite** — `.github/workflows/ci.yml` runs the exact same
`./mvnw verify` a developer runs locally, against real PostgreSQL 16.4
Testcontainers, with no tests skipped. See "Continuous Integration" below.

**Plain account lookup by id (`GET /api/v1/accounts/{id}`) is not
implemented** — no task has been assigned it so far. There is also no
authentication, no Kafka/event processing/outbox, and no
settlement/reconciliation — those are explicitly out of scope until later
Phase 2/3 tasks per `docs/TASKS.md` and `CLAUDE.md`.

## Idempotency

`POST /api/v1/accounts/{id}/deposits` and `POST /api/v1/transfers` are the
only two endpoints that require an `Idempotency-Key` header — account
creation and both read endpoints do not. The key makes each operation safe
to retry (e.g. after a network timeout):

- **Replay:** the same key with the same amount/currency/account(s) always
  returns the exact original response (same status, same body) — no new
  `ledger_transaction`, no new `ledger_entry` rows, no balance change,
  however many times it's retried.
- **Conflict:** the same key with *different* request data — a different
  amount, currency, account, or the other endpoint entirely (a deposit key
  replayed against `/transfers`, or vice versa) — returns `409 Conflict`.
- **Failed attempts don't consume the key:** if the underlying request
  fails (validation, insufficient funds, account not found, or a database
  error), no key is claimed — the same key can be retried once the problem
  is fixed.
- **Concurrency is PostgreSQL-native, not in-memory:** concurrent requests
  sharing the same key are serialized by a PostgreSQL transaction-scoped
  advisory lock, so exactly one financial write ever happens per key — this
  holds even across multiple application instances, since the lock lives
  in the database, not the JVM. See `docs/ARCHITECTURE.md`'s "Idempotency"
  section for the full mechanism.
- **Retention is indefinite** in Phase 2 — no expiry, no cleanup job, no
  delete endpoint.

Example (curl):

```
curl -i -X POST http://localhost:8080/api/v1/accounts/<account-id>/deposits \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8f14e45f-ceea-467e-bd48-9ffb2f9d1a30' \
  -d '{"amount": "100.00", "currency": "USD"}'

# Retrying with the same key and body replays the first response exactly:
curl -i -X POST http://localhost:8080/api/v1/accounts/<account-id>/deposits \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8f14e45f-ceea-467e-bd48-9ffb2f9d1a30' \
  -d '{"amount": "100.00", "currency": "USD"}'

# Same key, different amount -> 409 Conflict:
curl -i -X POST http://localhost:8080/api/v1/accounts/<account-id>/deposits \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8f14e45f-ceea-467e-bd48-9ffb2f9d1a30' \
  -d '{"amount": "200.00", "currency": "USD"}'

curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3c2f9b7a-6b34-4b7b-9a3a-1a8e0f2d5c11' \
  -d '{"sourceAccountId": "<source-id>", "destinationAccountId": "<destination-id>", "amount": "40.00", "currency": "USD"}'

# Retrying with the same key and body replays the first response exactly:
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3c2f9b7a-6b34-4b7b-9a3a-1a8e0f2d5c11' \
  -d '{"sourceAccountId": "<source-id>", "destinationAccountId": "<destination-id>", "amount": "40.00", "currency": "USD"}'

# Same key, different destination -> 409 Conflict:
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3c2f9b7a-6b34-4b7b-9a3a-1a8e0f2d5c11' \
  -d '{"sourceAccountId": "<source-id>", "destinationAccountId": "<other-destination-id>", "amount": "40.00", "currency": "USD"}'
```

These examples are illustrative (placeholder account ids) — they were not
run against a live server as part of this task; behavior is verified by
`IdempotencyIntegrationTest` against real PostgreSQL Testcontainers
instead. You can also exercise the header interactively via Swagger UI
(see below), which documents it as a required parameter on both endpoints.

## Transactional Outbox

Every newly committed deposit or transfer durably records exactly one
domain event — `DEPOSIT_COMPLETED` or `TRANSFER_COMPLETED` — in an
`outbox_event` table, in the **same PostgreSQL transaction** as the
ledger transaction, its two ledger entries, both accounts' balance
updates, and the Idempotency-Key record above. All of it commits together
or none of it does; there is no separate step and no way for one part to
succeed while another fails.

A background publisher (Task 12 — see "Kafka Publishing" below) later
sends each pending row to Kafka independently, without the deposit or
transfer request ever depending on Kafka's availability.

`outbox_event.published_at` is `NULL` until that publisher gets a
successful broker acknowledgement for the row. A Task 10 replay of an
already-completed deposit or transfer never inserts a second event for
the same transaction — the insertion point is only reachable when a
genuinely new financial write happens. Event rows are immutable (enforced
by database triggers, not just application code): they can never be
updated or deleted, except for the one permitted transition of
`published_at` from `NULL` to a timestamp, exactly once.

The event payload never includes the internal `EXTERNAL_FUNDING` account
id, the raw `Idempotency-Key`, account balances, or any internal
implementation detail — only the same kind of public fields already
visible in the deposit/transfer response (transaction id, account id(s),
amount as a four-decimal string, uppercase currency, and an ISO-8601 UTC
timestamp matching the ledger transaction's own `createdAt`). See
`docs/API_SPEC.md`'s note on Tasks 11–12 and `docs/ARCHITECTURE.md`'s
"Transactional Outbox" section for the full mechanism and the exact
version-1 payload schemas.

## Kafka Publishing

A background poller (`OutboxPublisherScheduler`) periodically selects a
bounded, deterministic batch of still-pending `outbox_event` rows
(oldest first) and hands each one to a per-event publisher
(`OutboxPublisher`) that, inside its own PostgreSQL transaction: claims
the row with `SELECT ... FOR UPDATE SKIP LOCKED` (safe across any number
of concurrent publisher threads or application instances — a row already
claimed elsewhere is simply skipped, never blocked on), sends the stored
payload to Kafka topic `ledger.transaction-events.v1` (configurable) with
the ledger transaction id as the record key, waits synchronously for the
broker's acknowledgement (`acks=all`, producer idempotence enabled), and
**only after that acknowledgement** sets `published_at` and commits. A
send failure leaves the row pending for a later polling cycle — it never
fails the original deposit/transfer request, since publishing happens
entirely after and independently of that request's own transaction.

**This is at-least-once publication, not exactly-once delivery.** A crash
between a successful Kafka acknowledgement and the `published_at` commit
can cause the same event to be published again on a later retry — this
window is real and is not hidden with Kafka transactions, `REQUIRES_NEW`,
or two-phase commit (see `docs/ARCHITECTURE.md`'s "Kafka Publishing"
section for exactly why). Every event's stable `eventId` is what will let
a future consumer (Task 13) detect and discard such a duplicate. **No
Kafka consumer exists in this repository yet** — Task 12 is publishing
only.

## Technology Stack

- Java 21
- Spring Boot 4.0.7
- Maven with Maven Wrapper
- PostgreSQL (via Docker Compose for local development)
- Flyway (`V1`: account/ledger schema, `V2`: idempotency, `V3`: transactional outbox)
- Spring Data JPA
- Spring Kafka (Task 12 — publishing only, no consumer)
- JUnit 5, Mockito, Testcontainers (including a Kafka Testcontainer)
- Spring Boot Actuator
- springdoc-openapi (OpenAPI 3 + Swagger UI)

## Prerequisites

- Java 21 (Maven Wrapper handles Maven itself)
- Docker (for PostgreSQL and Kafka via Docker Compose, and for
  Testcontainers-based integration tests)

## Environment Setup

1. Copy `.env.example` to `.env`:
   ```
   cp .env.example .env
   ```
2. Edit `.env` and set a real `POSTGRES_PASSWORD` (and optionally override
   `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PORT`, `KAFKA_PORT`). `.env`
   is gitignored — never commit it.

## Running PostgreSQL and Kafka

The application uses Spring Boot's Docker Compose support: when you run the
application locally, it automatically starts the `postgres` and `kafka`
services defined in `docker-compose.yml` using the values from `.env`. You
do not need to run `docker compose up` manually — starting the application
is enough. Kafka runs as a single-node KRaft broker (no ZooKeeper),
reachable at `localhost:${KAFKA_PORT:-9092}` — the same host:port
`spring.kafka.bootstrap-servers` defaults to (see `application.yml`).

If you want to start both independently:
```
docker compose up -d
```

## Running the Application

```
./mvnw spring-boot:run
```

The application starts on port `8080` by default.

## Actuator Health Check

Once running:
```
curl http://localhost:8080/actuator/health
```

## Viewing the API Documentation

Once running, open Swagger UI in a browser:

```
http://localhost:8080/swagger-ui/index.html
```

Or fetch the raw OpenAPI 3.1 document:

```
curl http://localhost:8080/v3/api-docs
```

The document is generated from the actual controllers and DTOs, not a
hand-maintained file, and covers exactly the five implemented endpoints
(account creation, deposit, transfer, balance, transaction history) plus
the shared error-response schema. No authentication scheme is declared.

## Manually Verifying Kafka Publishing

The steps below describe how to verify Task 12 end to end locally; they
were not run as part of writing this documentation — treat them as
instructions, not a claim that this exact sequence was executed.

1. Start PostgreSQL and Kafka: `docker compose up -d` (or just start the
   application — see "Running PostgreSQL and Kafka" above).
2. Start the application with publisher scheduling enabled (the default):
   `./mvnw spring-boot:run`.
3. Submit a deposit with an Idempotency-Key:
   ```
   curl -i -X POST http://localhost:8080/api/v1/accounts/<account-id>/deposits \
     -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 8f14e45f-ceea-467e-bd48-9ffb2f9d1a30' \
     -d '{"amount": "100.00", "currency": "USD"}'
   ```
4. Inspect the topic with a console consumer:
   ```
   docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 \
     --topic ledger.transaction-events.v1 \
     --from-beginning --property print.key=true
   ```
5. Confirm the printed record's key is the deposit response's
   `transactionId` (a plain UUID string).
6. Confirm the printed JSON value matches the stored outbox payload —
   `eventType: "DEPOSIT_COMPLETED"`, the same `transactionId`,
   `destinationAccountId`, `amount` (four decimals), and `currency`.
7. Confirm `published_at` is populated (non-null) for that row once the
   next poll cycle runs (`ledgerguard.outbox.publisher.poll-delay-millis`,
   default 2000ms).
8. Retry the same deposit (same Idempotency-Key, same body).
9. Confirm no second outbox row and no second Kafka record are produced —
   the retry returns the identical original response.
10. Stop Kafka (`docker compose stop kafka`), submit a new deposit with a
    fresh Idempotency-Key, and confirm the request still succeeds (201)
    and its `outbox_event` row's `published_at` remains `NULL`.
11. Restart Kafka (`docker compose start kafka`) and confirm that pending
    event is published on a later poll, without resubmitting the request.

## Running Tests

```
./mvnw verify
```

This runs the full test suite (245 tests), including Testcontainers-backed
integration tests that each start an isolated PostgreSQL container
(independent of the Docker Compose service above): a connectivity smoke
test (`SELECT 1` against the datasource), a schema-verification test that
confirms the `V1` Flyway migration applies and every table, constraint,
index, and trigger behaves as designed, an account-creation test suite, a
deposit test suite, a transfer test suite, an account
balance/transaction-history test suite, a global error-handling test
suite, an OpenAPI documentation test suite, an idempotency test suite
(Task 10), an outbox test suite plus a small unit-test suite (Task 11),
and a Kafka publisher test suite plus a small configuration-validation
unit-test suite (Task 12). The idempotency suite verifies header
validation, exact response replay, same- and cross-operation conflict
detection, that a failed attempt never consumes the key, and — against
real PostgreSQL advisory locking, never mocks or Java-only
synchronization, with bounded timeouts rather than `Thread.sleep` — that
concurrent requests sharing a key produce exactly one financial write.
The outbox suite verifies exactly one event row per new deposit/transfer
with the approved payload shape, no duplicate event on any idempotent
replay (including concurrently), full rollback of the event alongside
the ledger/idempotency state on any failure — including a forced,
genuine outbox-insertion failure — and that every database-level
constraint, immutability trigger, and the `V1`–`V3` migration sequence
all behave exactly as documented. The Kafka publisher suite runs against
a **real Kafka Testcontainer** (never a mocked broker): it verifies topic/
partition creation, exact key/value publication for both deposits and
transfers, that idempotent replays and conflicts never produce a second
record, that a genuine broker-connection failure leaves `published_at`
unset without touching financial or idempotency state (and that the same
event publishes successfully once retried against a working broker), that
one failed candidate never blocks a later one, safe concurrent publishing
of both the same event and distinct events, deterministic candidate
ordering/bounded batching, and that the V3 immutability triggers remain
fully effective after a real publish. The deposit and transfer suites
both verify balanced double-entry
postings, balance correctness, a genuine database-failure rollback
scenario, and real concurrency against PostgreSQL row locking (no mocks,
no Java-only synchronization) — the transfer suite additionally proves
concurrent transfers from one source never overspend it, and that
concurrent opposite-direction transfers between the same two accounts
complete without deadlocking. The balance/history suite proves reads never
create ledger rows or change balances, history ordering and pagination
match the approved contract exactly, and no account's history ever leaks
another account's entries. The error-handling suite proves every
endpoint's error responses share the one documented envelope, contain no
internal implementation detail, and that every rejected write still
leaves no partial financial state. The OpenAPI suite proves the generated
document matches the real implementation exactly — correct paths,
schemas, required fields, status codes, and pagination contract — with no
internal detail leaked and no undeclared security scheme. Each test's
PostgreSQL container starts and stops automatically as part of the test
run — no manually running database is required.

## Continuous Integration

`.github/workflows/ci.yml` runs on every push and pull request targeting
`master`. One job, `ubuntu-latest`, Java 21 (Temurin), runs:

```
./mvnw --batch-mode --no-transfer-progress verify
```

— the same authoritative command shown above, so a green local `./mvnw
verify` is the real local equivalent of CI. GitHub-hosted runners have
Docker preinstalled and running, so Testcontainers starts real
`postgres:16.4` containers on the runner exactly as it does locally; the
workflow never uses the local `docker-compose.yml` service, H2, or a
shared/long-lived database. The workflow requests `permissions: contents:
read` only — it never writes to the repository and contains no
deployment, publishing, or release step. On failure, Surefire/Failsafe
reports are uploaded as a short-retention build artifact for diagnosis.

## Documentation

- `docs/TASKS.md` — Phase 1 task breakdown and progress
- `docs/REQUIREMENTS.md` — Phase 1 scope, acceptance criteria, and current
  limitations
- `docs/ARCHITECTURE.md` — package structure and architectural decisions
  (database layer, account creation, deposit, transfer, account-query,
  error-handling, API-documentation, CI, idempotency, the transactional
  outbox, and Kafka publishing are all implemented)
- `docs/DATA_MODEL.md` — account/ledger schema and accounting semantics.
  The `account`/`ledger_transaction`/`ledger_entry` schema is implemented
  (Flyway V1); account creation, deposits, and transfers all read/write
  them; the balance/history endpoints read all three but write none of
  them. `idempotency_key` (Flyway V2, Task 10) and `outbox_event`
  (Flyway V3, Task 11) are both separate tables; Task 12 added no new
  migration.
- `docs/API_SPEC.md` — `POST /api/v1/accounts`, `POST
  /api/v1/accounts/{id}/deposits`, `POST /api/v1/transfers`, `GET
  /api/v1/accounts/{id}/balance`, and `GET
  /api/v1/accounts/{id}/transactions` are all implemented and documented
  exactly as built, including the shared error-response contract, the
  OpenAPI/Swagger endpoints, and the `Idempotency-Key` contract (Task 10);
  the remaining endpoints are still planned contracts.
- `docs/TEST_STRATEGY.md` — testing approach for Phase 1/2; every test
  suite is implemented and all of them run automatically in CI (Task 9)
