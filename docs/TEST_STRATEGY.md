# Test Strategy

> **Status: planning document; schema-level (Task 2) and account-creation
> (Task 3) tests are now implemented.** As of Task 3, the connectivity
> smoke test (Task 1), schema-verification tests (Task 2), and account
> creation's HTTP-boundary + persisted-state tests (Task 3) all exist (see
> "Currently Implemented" below). Business-logic tests that require the
> future ledger-posting domain service — deposit/transfer invariant tests,
> concurrency tests, and the 404-for-SYSTEM-account tests for `GET
> /api/v1/accounts/{id}` and later endpoints — remain unwritten, since the
> code they would exercise doesn't exist yet.

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

`AccountCreationIntegrationTest` (Task 3) — HTTP-boundary tests via
`TestRestTemplate` against `POST /api/v1/accounts`, plus direct JDBC checks
of the persisted `account` row, all against a Testcontainers-provisioned
`postgres:16.4` instance running the Flyway migrations from scratch:
- Valid USD account creation: 201, correct response fields
  (`id`/`ownerName`/`currency`/`balance`/`createdAt`), and the persisted row
  has `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`/`USD`/zero balance.
- Lowercase `"usd"` is normalized to `"USD"` in the response.
- Missing currency, malformed currency (400), and unsupported-but-well-formed
  non-USD currency (422) are all rejected, and none of these persist a row.
- An attempt to set `accountCategory`, `accountClass`, `accountPurpose`,
  `balance`, `id`, or `createdAt` via extra JSON properties is rejected
  (400, unrecognized property) and persists nothing — proving a client
  cannot create a `SYSTEM` account or choose any protected field.
- A direct repository save of an invalid taxonomy combination (bypassing
  `AccountService` entirely) still fails against
  `chk_account_taxonomy_combination` — proving the JPA mapping doesn't
  weaken the schema's guarantees.

## CI

`./mvnw verify` runs the full suite, including Testcontainers integration
tests, and is required to pass before any task is marked done (per
`CLAUDE.md`'s Definition of Done). GitHub Actions wiring is planned for
Task 9 and does not exist yet.
