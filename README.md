# LedgerGuard

LedgerGuard is a portfolio-quality transaction ledger and reconciliation
platform, built to demonstrate backend software engineering practices:
double-entry accounting, transactional correctness, and test-driven
development against a real database.

## Status: Phase 1, Task 3 — Account Creation

This repository contains the project foundation (Task 1), the initial
database schema (Task 2), and account creation (Task 3): `POST
/api/v1/accounts` creates a **customer USD wallet account**. Every account
created through this endpoint always opens with a **zero balance** and the
server-assigned `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` taxonomy — a client
cannot choose the account category, class, purpose, currency (other than
USD), initial balance, id, or creation timestamp.

**Deposits, transfers, balance lookups, transaction history, and account
lookup by id (`GET /api/v1/accounts/{id}`) are not implemented.** The
`ledger_transaction` and `ledger_entry` tables exist in the database but no
Java code reads or writes them yet — there is no ledger posting, no
double-entry transactions, and no balanced-entry logic in the running
application. There is also no authentication, no Kafka/event processing,
and no reconciliation — those are explicitly out of scope until later
tasks/phases per `docs/TASKS.md` and `CLAUDE.md`.

## Technology Stack

- Java 21
- Spring Boot 4.0.7
- Maven with Maven Wrapper
- PostgreSQL (via Docker Compose for local development)
- Flyway (dependency present; no migrations written yet)
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
behaves as designed, and an account-creation test suite that exercises
`POST /api/v1/accounts` over real HTTP and checks the persisted database
row directly. Each test's PostgreSQL container starts and stops
automatically as part of the test run — no manually running database is
required.

## Documentation

- `docs/TASKS.md` — Phase 1 task breakdown and progress
- `docs/REQUIREMENTS.md` — Phase 1 scope, acceptance criteria, and current
  limitations
- `docs/ARCHITECTURE.md` — package structure and architectural decisions
  (database layer and account creation are implemented; deposit/transfer
  sections are forward-looking and not yet built)
- `docs/DATA_MODEL.md` — account/ledger schema and accounting semantics.
  The schema is implemented (Flyway V1); account creation reads/writes the
  `account` table; nothing yet reads or writes `ledger_transaction`/
  `ledger_entry`.
- `docs/API_SPEC.md` — `POST /api/v1/accounts` is implemented and documented
  exactly as built; the remaining endpoints are still planned contracts.
- `docs/TEST_STRATEGY.md` — testing approach for Phase 1; schema-level and
  account-creation tests are implemented, deposit/transfer business-logic
  tests are planned
