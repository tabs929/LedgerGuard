# LedgerGuard

LedgerGuard is a portfolio-quality transaction ledger and reconciliation
platform, built to demonstrate backend software engineering practices:
double-entry accounting, transactional correctness, and test-driven
development against a real database.

## Status: Phase 1, Task 2 — Flyway Account and Ledger Schema

This repository currently contains the project foundation (Task 1) plus the
initial database schema (Task 2): a runnable Spring Boot application
skeleton, PostgreSQL via Docker Compose, and a Flyway migration
(`V1__init_account_ledger_schema.sql`) that creates the `account`,
`ledger_transaction`, and `ledger_entry` tables with their constraints,
indexes, and immutability triggers, verified by PostgreSQL Testcontainers
integration tests.

**No financial functionality exists yet.** There are no JPA entities, no
repositories, no services, and no controllers beyond Actuator's health
endpoint — so there is no account creation, no deposits, no transfers, and
no balance or transaction-history APIs. The schema exists in the database,
but nothing in the application reads or writes it yet. There is also no
authentication, no Kafka/event processing, and no reconciliation — those
are explicitly out of scope until later tasks/phases per `docs/TASKS.md`
and `CLAUDE.md`.

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
against the datasource) and a schema-verification test that confirms the
Flyway migration applies and every table, constraint, index, and trigger
behaves as designed. Each test's PostgreSQL container starts and stops
automatically as part of the test run — no manually running database is
required.

## Documentation

- `docs/TASKS.md` — Phase 1 task breakdown and progress
- `docs/REQUIREMENTS.md` — Phase 1 scope, acceptance criteria, and current
  limitations
- `docs/ARCHITECTURE.md` — package structure and architectural decisions
  (database-layer sections are implemented; Java-layer sections are
  forward-looking and not yet built)
- `docs/DATA_MODEL.md` — account/ledger schema and accounting semantics.
  The schema is implemented (Flyway V1); no Java code reads or writes it yet.
- `docs/API_SPEC.md` — planned API contracts (not yet implemented)
- `docs/TEST_STRATEGY.md` — testing approach for Phase 1; schema-level
  tests are implemented, business-logic tests are planned
