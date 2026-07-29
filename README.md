# LedgerGuard

LedgerGuard is a portfolio-quality transaction ledger and reconciliation
platform, built to demonstrate backend software engineering practices:
double-entry accounting, transactional correctness, and test-driven
development against a real database.

## Status: Phase 1, Task 1 — Project Foundation

This repository currently contains **only the project foundation**: a
runnable Spring Boot application skeleton, PostgreSQL via Docker Compose,
and a Testcontainers-backed integration test proving database connectivity.

**No financial functionality exists yet.** There are no accounts, no
deposits, no transfers, no ledger, no balance or transaction-history APIs,
and no database schema beyond an empty Flyway migration folder. There is
also no authentication, no Kafka/event processing, and no reconciliation —
those are explicitly out of scope until later tasks/phases per
`docs/TASKS.md` and `CLAUDE.md`.

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

This runs the full test suite, including a Testcontainers-backed
integration test that starts an isolated PostgreSQL container (independent
of the Docker Compose service above), verifies the Spring application
context loads, and confirms connectivity by executing `SELECT 1` against
the datasource. The test's PostgreSQL container starts and stops
automatically as part of the test run — no manually running database is
required.

## Documentation

- `docs/TASKS.md` — Phase 1 task breakdown and progress
- `docs/REQUIREMENTS.md` — Phase 1 scope, acceptance criteria, and current
  limitations
- `docs/ARCHITECTURE.md` — package structure and architectural decisions
  (largely forward-looking; most described components are not yet built)
- `docs/DATA_MODEL.md` — planned account/ledger schema and accounting
  semantics (not yet implemented — no migrations exist yet)
- `docs/API_SPEC.md` — planned API contracts (not yet implemented)
- `docs/TEST_STRATEGY.md` — planned testing approach for Phase 1
