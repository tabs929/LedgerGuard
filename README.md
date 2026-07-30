# LedgerGuard

LedgerGuard is a portfolio-quality transaction ledger and reconciliation
platform, built to demonstrate backend software engineering practices:
double-entry accounting, transactional correctness, and test-driven
development against a real database.

## Status: Phase 1, Task 5 — Transfers

This repository contains the project foundation (Task 1), the initial
database schema (Task 2), account creation (Task 3), deposits (Task 4), and
transfers (Task 5).

`POST /api/v1/accounts` creates a **customer USD wallet account**. Every
account created through this endpoint always opens with a **zero balance**
and the server-assigned `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` taxonomy —
a client cannot choose the account category, class, purpose, currency
(other than USD), initial balance, id, or creation timestamp.

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

**Balance lookups, transaction history, and account lookup by id (`GET
/api/v1/accounts/{id}`) are not implemented.** There is also no
authentication, no Kafka/event processing, and no reconciliation — those
are explicitly out of scope until later tasks/phases per `docs/TASKS.md`
and `CLAUDE.md`.

## Technology Stack

- Java 21
- Spring Boot 4.0.7
- Maven with Maven Wrapper
- PostgreSQL (via Docker Compose for local development)
- Flyway (V1 migration: account and ledger schema)
- Spring Data JPA
- JUnit 5, Mockito, Testcontainers
- Spring Boot Actuator

## Prerequisites

- Java 21 (Maven Wrapper handles Maven itself)
- Docker (for PostgreSQL via Docker Compose, and for Testcontainers-based
  integration tests)

## Environment Setup

1. Copy `.env.example` to `.env`:
   ```
   cp .env.example .env
   ```
2. Edit `.env` and set a real `POSTGRES_PASSWORD` (and optionally override
   `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PORT`). `.env` is gitignored —
   never commit it.

## Running PostgreSQL

The application uses Spring Boot's Docker Compose support: when you run the
application locally, it automatically starts the `postgres` service defined
in `docker-compose.yml` using the values from `.env`. You do not need to run
`docker compose up` manually — starting the application is enough.

If you want to start the database independently:
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

## Running Tests

```
./mvnw verify
```

This runs the full test suite, including Testcontainers-backed integration
tests that each start an isolated PostgreSQL container (independent of the
Docker Compose service above): a connectivity smoke test (`SELECT 1`
against the datasource), a schema-verification test that confirms the
Flyway migration applies and every table, constraint, index, and trigger
behaves as designed, an account-creation test suite, a deposit test suite,
and a transfer test suite. The deposit and transfer suites both verify
balanced double-entry postings, balance correctness, a genuine
database-failure rollback scenario, and real concurrency against
PostgreSQL row locking (no mocks, no Java-only synchronization) — the
transfer suite additionally proves concurrent transfers from one source
never overspend it, and that concurrent opposite-direction transfers
between the same two accounts complete without deadlocking. Each test's
PostgreSQL container starts and stops automatically as part of the test
run — no manually running database is required.

## Documentation

- `docs/TASKS.md` — Phase 1 task breakdown and progress
- `docs/REQUIREMENTS.md` — Phase 1 scope, acceptance criteria, and current
  limitations
- `docs/ARCHITECTURE.md` — package structure and architectural decisions
  (database layer, account creation, deposit, and transfer processing are
  all implemented)
- `docs/DATA_MODEL.md` — account/ledger schema and accounting semantics.
  The schema is implemented (Flyway V1); account creation, deposits, and
  transfers all read/write `account`, `ledger_transaction`, and
  `ledger_entry`.
- `docs/API_SPEC.md` — `POST /api/v1/accounts`, `POST
  /api/v1/accounts/{id}/deposits`, and `POST /api/v1/transfers` are
  implemented and documented exactly as built; the remaining endpoints are
  still planned contracts.
- `docs/TEST_STRATEGY.md` — testing approach for Phase 1; schema-level,
  account-creation, deposit, and transfer tests are all implemented
