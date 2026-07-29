# Architecture

> **Status: planning document, with the database layer now implemented.**
> As of Task 2, the schema described in "Ledger as Source of Truth vs.
> Materialized Balance", "Deterministic Lock Ordering" (the row shape it
> relies on), "Ledger Immutability", and "Money and Currency" exists in the
> database (`V1__init_account_ledger_schema.sql`). The `account`, `ledger`,
> and `transfer` **Java packages** described below still do not exist —
> there are no entities, repositories, services, or controllers yet. The
> locking behavior, transaction boundaries, and public/internal lookup
> split are architectural decisions for the future domain service (Task 3+)
> and are not yet implemented in code.

## Style

LedgerGuard is a modular monolith organized by business capability, not by
technical layer. No microservices.

## Package Structure (Phase 1)

Only these packages are created in Phase 1, each only when the task that
needs it starts — no empty placeholder packages are scaffolded in advance:

- `account` — account creation, lookup, and the public/internal repository
  split described below.
- `ledger` — `LedgerTransaction` and `LedgerEntry` persistence.
- `transfer` — the transfer use case (spans `account` and `ledger`).
- `common` — shared error-handling and cross-cutting types (e.g.
  `ApiExceptionHandler`, `ApiError`).

Packages named in `CLAUDE.md` for later phases (`idempotency`, `outbox`,
`settlement`, `reconciliation`, `security`, `audit`) are **not** created in
Phase 1.

## Transaction Boundaries

**Not yet implemented.** Each business operation (deposit, transfer) is
planned to run as a single `@Transactional` service method that performs
all of its ledger-entry inserts and balance updates together, rolling back
fully on any failure. No such service exists yet — Task 2 only creates the
tables and constraints those future writes will target.

## Ledger as Source of Truth vs. Materialized Balance

`ledger_entry` rows are the authoritative financial record; `account.balance`
is a **materialized balance** column, present in the schema
(`account.balance NUMERIC(19,4) NOT NULL DEFAULT 0`, `CHECK (balance >= 0)`)
as of Task 2. The rule that it's written only in lockstep with ledger-entry
inserts, and the integration test that recomputes balances from entries and
compares them, both require the future write path — they are not yet
implemented.

## Deterministic Lock Ordering (planned, not yet implemented)

Both deposits and transfers touch two account rows (transfer: sender +
recipient; deposit: customer account + the shared `EXTERNAL_FUNDING`
account). Both rows are locked with `SELECT ... FOR UPDATE`, always in
ascending account-`id` (UUID) order — never by role (not
source-then-destination, not customer-then-funding) — before any validation
or write.

This matters for two distinct reasons:
- **Transfers:** locking by role instead of a fixed order can deadlock when
  two concurrent transfers run in opposite directions (A→B and B→A).
- **Deposits:** concurrent deposits to *different* customers still both
  write the same shared `EXTERNAL_FUNDING` row; locking only the customer
  row would leave that shared row exposed to a lost update.

## Ledger Immutability (implemented, Task 2)

`ledger_entry` and `ledger_transaction` are both part of the authoritative
financial record. Both have a `BEFORE UPDATE OR DELETE` PostgreSQL trigger
(`trg_ledger_entry_immutable`, `trg_ledger_transaction_immutable`) that
unconditionally rejects mutation — `INSERT` remains allowed. This is a
database-level guard rather than differentiated role grants, because the
project currently uses a single application database role — grants alone
would provide no real protection. Verified directly against PostgreSQL in
`SchemaMigrationIntegrationTest` (`UPDATE`/`DELETE` on either table raise
the trigger's exception).

## Public vs. Internal Account Lookup (planned, not yet implemented)

The `account` **package** (not yet created) will expose two distinct
repository lookup paths:
- A **public-lookup** method (e.g. `findPubliclyVisibleById`), used by every
  controller/service reachable from a public endpoint. It never returns a
  `SYSTEM`-category account, so a request naming a `SYSTEM` account id
  (such as `EXTERNAL_FUNDING`) resolves as "not found" (404) — identical to
  a nonexistent id, disclosing nothing about whether the id is valid.
- An **internal-lookup** method (e.g.
  `findSystemAccountByPurposeAndCurrency`), used only by the deposit (and
  future withdrawal) posting logic to reach `EXTERNAL_FUNDING`.

This means the 404-for-SYSTEM-accounts behavior falls out of which
repository method a code path uses, rather than being an ad hoc filter
added to each controller.

## Money and Currency (schema implemented, Task 2)

`NUMERIC(19,4)` in PostgreSQL (never `FLOAT`/`REAL`/`DOUBLE PRECISION`),
present on `account.balance` and `ledger_entry.amount`. Currency is
`VARCHAR(3)`, constrained to a three-uppercase-letter ISO 4217 shape at the
database level (`chk_account_currency_format`,
`chk_ledger_entry_currency_format`). The stricter "USD only" rule is an
application-level validation planned for account creation (Task 3) — the
database schema itself accepts any ISO-4217-shaped code, by design, so a
future currency can be added without a schema redesign. The `BigDecimal`
mapping in Java does not exist yet — no entities exist.
