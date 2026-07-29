# Test Strategy

> **Status: planning document.** Describes the approved Phase 1 testing
> approach. As of Task 1, only a foundational connectivity test exists (see
> "Currently Implemented" below) — none of the business-logic or invariant
> tests described here have been written yet.

## Split

- **Unit tests (Mockito):** service-layer logic with mocked repositories —
  validation rules, currency-mismatch rejection, insufficient-funds
  rejection, self-transfer rejection, DTO mapping. No DB behavior is ever
  mocked.
- **Integration tests (PostgreSQL via Testcontainers):** anything touching
  persistence, transactions, or concurrency. **H2 is never used as a
  substitute for PostgreSQL.**

## Testcontainers Usage

Integration tests start an isolated `postgres:16.4` container per test
class via JUnit 5's `@Testcontainers` + `@Container`, wired into Spring
through `@ServiceConnection`. This is independent of the `docker-compose.yml`
service used for local development — the two are never conflated. The
`test` Spring profile disables Spring Boot's Docker Compose lifecycle
(`spring.docker.compose.enabled: false`) precisely so tests never
accidentally depend on, or fight with, a developer's locally running
Docker Compose Postgres.

## Planned Invariant Tests (not yet written)

- **Ledger-as-source-of-truth:** after a sequence of deposits and transfers,
  recompute each account's balance by summing its `ledger_entry` rows per
  the account's class formula, and assert equality with the materialized
  `account.balance`.
- **Trial-balance invariant:** after each deposit and transfer, assert
  `SUM(debit amounts) == SUM(credit amounts)` for that transaction's
  entries, and system-wide `SUM(asset balances) == SUM(liability balances)`.
- **Deterministic-locking / deadlock test:** simultaneous A→B and B→A
  transfers between the same two accounts complete without deadlock.
- **Concurrent-deposit test:** concurrent deposits into different customer
  accounts (racing on the shared `EXTERNAL_FUNDING` row) all succeed with
  no lost updates.
- **Immutability tests:** direct `UPDATE`/`DELETE` against `ledger_entry`
  and `ledger_transaction` rows both fail due to the database trigger.
- **Uniqueness test:** a second `SYSTEM`/`EXTERNAL_FUNDING`/USD account
  insert fails the partial unique index.
- **Taxonomy-combination test:** an invalid `(category, class, purpose)`
  combination fails the cross-column `CHECK` constraint.
- **Validation/rejection tests:** self-transfer, unsupported currency,
  currency mismatch, zero/negative amount, insufficient funds, missing
  account.
- **SYSTEM-account-as-404 tests:** every public endpoint returns 404 (not a
  distinct error) for a `SYSTEM` account id.

## Currently Implemented

`LedgerGuardApplicationTests` (Task 1):
- `contextLoads()` — the Spring application context starts successfully.
- `canConnectToTestcontainerDatabase()` — runs `SELECT 1` through the
  application's configured `DataSource` against a Testcontainers-provisioned
  `postgres:16.4` instance, proving real PostgreSQL connectivity end to end.

## CI

`./mvnw verify` runs the full suite, including Testcontainers integration
tests, and is required to pass before any task is marked done (per
`CLAUDE.md`'s Definition of Done). GitHub Actions wiring is planned for
Task 9 and does not exist yet.
