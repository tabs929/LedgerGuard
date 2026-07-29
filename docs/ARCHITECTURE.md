# Architecture

> **Status: planning document.** Describes the approved architecture for
> Phase 1. As of Task 1, only the application skeleton exists — the
> `account`, `ledger`, and `transfer` packages described below do not exist
> yet in the codebase. They will be created starting in Task 2/3, only when
> the task that needs them begins (see `docs/TASKS.md`).

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

Each business operation (deposit, transfer) is implemented as a single
`@Transactional` service method that performs all of its ledger-entry
inserts and balance updates together. Any failure inside that method
(insufficient funds, account not found, currency mismatch, constraint
violation) triggers a full rollback — no partial writes are possible.

## Ledger as Source of Truth vs. Materialized Balance

`ledger_entry` rows are the authoritative financial record. `account.balance`
is a **materialized balance** — written in the same database transaction as
the ledger entries that justify it, purely for read efficiency and overdraft
checks. It is never written independently of a ledger-entry insert, and its
correctness is always provable by summing the account's entries. Integration
tests recompute balances from ledger entries and assert equality with the
materialized `account.balance` (see `docs/TEST_STRATEGY.md`).

## Deterministic Lock Ordering

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

## Ledger Immutability

`ledger_entry` and `ledger_transaction` are both part of the authoritative
financial record. Both get a `BEFORE UPDATE OR DELETE` PostgreSQL trigger
that unconditionally rejects mutation. This is a database-level guard rather
than differentiated role grants, because the project currently uses a
single application database role — grants alone would provide no real
protection.

## Public vs. Internal Account Lookup

The `account` package exposes two distinct repository lookup paths:
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

## Money and Currency

`NUMERIC(19,4)` in PostgreSQL, mapped to `BigDecimal` in Java. Currency is
`VARCHAR(3)` holding an ISO 4217 code. Never `float`/`double` for money.
