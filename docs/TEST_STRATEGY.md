# Test Strategy

> **Status: planning document, schema-level tests now implemented.**
> Describes the approved Phase 1 testing approach. As of Task 2, the
> connectivity smoke test (Task 1) and a full set of schema-verification
> tests (Task 2) exist (see "Currently Implemented" below). Business-logic
> tests that require a domain service — invariant tests over actual
> deposits/transfers, concurrency tests, validation/rejection tests, and
> the 404-for-SYSTEM-account tests — remain unwritten, since the code they
> would exercise doesn't exist yet.

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

## Schema-Level Tests (implemented, Task 2)

`SchemaMigrationIntegrationTest` verifies the V1 migration directly against
a Testcontainers-provisioned `postgres:16.4` instance, using plain JDBC
(`DataSource`/`Connection`) — no JPA entities or repositories exist yet, so
these tests talk to the database directly:

- **Migration applies:** Flyway migrates a fresh database to version 1
  successfully (`flyway_schema_history` shows `success = true`); all three
  tables exist.
- **Constraints, indexes, and triggers exist:** queried directly from
  `pg_constraint`, `pg_indexes`, and `pg_trigger`.
- **Taxonomy-combination test:** the two valid `(category, class, purpose)`
  combinations are accepted; an invalid combination
  (`CUSTOMER + ASSET + EXTERNAL_FUNDING`) is rejected by
  `chk_account_taxonomy_combination`.
- **Currency-format test:** a malformed currency code is rejected by
  `chk_account_currency_format`.
- **Non-negative-balance test:** a negative `account.balance` is rejected by
  `chk_account_balance_nonneg`.
- **Positive-amount test:** a zero-amount `ledger_entry` is rejected by
  `chk_ledger_entry_amount_positive`.
- **Uniqueness test:** a second `SYSTEM`/`EXTERNAL_FUNDING`/`USD` account
  insert fails `uq_system_account_purpose_currency` (the migration already
  seeds one such row).
- **Valid insert test:** a `ledger_transaction` with two `ledger_entry` rows
  (one `DEBIT`, one `CREDIT`) referencing valid accounts inserts
  successfully — proving the foreign keys and row-level constraints don't
  block legitimate double-entry postings.
- **Immutability tests:** direct `UPDATE`/`DELETE` against both
  `ledger_entry` and `ledger_transaction` rows are rejected by their
  triggers; `INSERT` is unaffected.

**Not covered by these tests, by design:** the "total debits equal total
credits per transaction" trial-balance invariant, ledger-as-source-of-truth
balance recomputation, deterministic-locking/deadlock behavior, and the
404-for-SYSTEM-account API behavior. These require the future domain
service and are planned below, not implemented in Task 2.

## Planned Invariant Tests (require the future domain service — not yet written)

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

`SchemaMigrationIntegrationTest` (Task 2) — see "Schema-Level Tests" above.

## CI

`./mvnw verify` runs the full suite, including Testcontainers integration
tests, and is required to pass before any task is marked done (per
`CLAUDE.md`'s Definition of Done). GitHub Actions wiring is planned for
Task 9 and does not exist yet.
