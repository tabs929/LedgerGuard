# Architecture

> **Status: database layer (Task 2), account creation (Task 3), and
> deposits (Task 4) implemented; transfers and beyond are still planning.**
> The `account` package now has account creation and deposit processing;
> the `ledger` package now exists (`LedgerTransaction`, `LedgerEntry`, and
> their repositories/enums). Transaction boundaries, deterministic lock
> ordering, and the ledger-as-source-of-truth write path are implemented
> for deposits — see "Deposit Processing" below. The `transfer` package
> still does not exist. `GET /api/v1/accounts/{id}` still does not exist,
> so the public-vs-internal lookup split described below is only partially
> realized (see that section for exactly what deposits do instead).

## Style

LedgerGuard is a modular monolith organized by business capability, not by
technical layer. No microservices.

## Package Structure (Phase 1)

Only these packages are created in Phase 1, each only when the task that
needs it starts — no empty placeholder packages are scaffolded in advance:

- `account` — **account creation (Task 3) and deposits (Task 4)
  implemented**; account lookup by id remains unimplemented.
- `ledger` — **implemented (Task 4)**: `LedgerTransaction`, `LedgerEntry`,
  `TransactionType`, `TransactionStatus`, `LedgerEntryType`, and their
  repositories.
- `transfer` — the transfer use case (spans `account` and `ledger`); not
  yet implemented.
- `common` — shared error-handling and cross-cutting types (e.g.
  `ApiExceptionHandler`, `ApiError`).

Packages named in `CLAUDE.md` for later phases (`idempotency`, `outbox`,
`settlement`, `reconciliation`, `security`, `audit`) are **not** created in
Phase 1.

## Account Creation (implemented, Task 3)

`AccountController` → `AccountService` → `AccountRepository` → `account`
table. `AccountService.createCustomerWalletAccount` runs inside a single
`@Transactional` method: it normalizes and validates the currency, then
constructs an `Account` with a **fixed** taxonomy
(`CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`) and a **fixed** zero opening
balance — neither is read from the request, because
`CreateAccountRequest` has no fields for them. There is no way for a
caller to reach a code path that sets category, class, purpose, balance,
id, or `createdAt`, since the DTO that crosses the HTTP boundary simply
doesn't carry those fields, and unrecognized JSON properties are rejected
outright (`spring.jackson.deserialization.fail-on-unknown-properties`)
rather than silently ignored. `createdAt` is populated by the database's
`DEFAULT now()` and read back after insert (the entity is refreshed via
`EntityManager.refresh` after `save()+flush()`, since Hibernate's
insert-time generated-value refresh did not reliably populate a
`TIMESTAMPTZ` column mapped to `Instant` in local testing against
PostgreSQL 16.4 / Hibernate 7.2).

Error handling is intentionally minimal for this one endpoint: Spring's
default handling covers bean-validation failures and unknown-JSON-property
rejections (400); a small `@ExceptionHandler` local to `AccountController`
covers the one domain-specific case, unsupported currency (422). This is
not the global exception framework — that is Task 7's responsibility.

## Deposit Processing (implemented, Task 4)

`AccountController.deposit` (`POST /api/v1/accounts/{id}/deposits`) →
`DepositService.deposit` → `AccountRepository` +
`LedgerTransactionRepository` + `LedgerEntryRepository`. The whole operation
runs inside one `@Transactional` service method:

1. Normalize/validate the request currency (must be `USD`).
2. Lock the destination customer account **and** the internally-resolved
   `EXTERNAL_FUNDING` account for that currency in a single query, in
   ascending account-id order (see "Deterministic Lock Ordering" below).
3. Confirm the locked destination row is actually
   `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` (a `SYSTEM` account id, or any
   other taxonomy, is treated as 404 — see "Public vs. Internal Account
   Lookup" below) and that its currency matches the request.
4. Insert one `LedgerTransaction` (`DEPOSIT`/`COMPLETED`).
5. Insert two `LedgerEntry` rows: `DEBIT` the funding account, `CREDIT` the
   destination account, both for the same amount and currency, both
   referencing the transaction just inserted.
6. Increase both accounts' materialized `balance` in place (plain field
   mutation on the already-locked, already-managed `Account` entities —
   Hibernate's dirty checking schedules the `UPDATE`s).
7. Flush explicitly, so any database-level failure (a constraint violation,
   a numeric overflow) surfaces here, inside the transaction, before a
   response is ever built — see "Transaction Boundary and Rollback" below.

The request DTO (`DepositRequest`) has fields for `amount` and `currency`
only — there is no field for the funding account, transaction id/type/
status, entry direction, ledger-entry ids, balances, timestamps, or account
taxonomy, so none of these can be supplied or overridden by the client. The
funding account is never looked up by a client-supplied id; it is resolved
purely from its protected taxonomy (`SYSTEM`/`ASSET`/`EXTERNAL_FUNDING`)
and the request's currency.

### Transaction Boundary and Rollback

If any step above throws — validation failure, `AccountNotFoundException`,
`CurrencyMismatchException`, or a database-level error surfaced at the
explicit flush — the exception propagates out of the `@Transactional`
method uninterrupted (nothing catches and swallows it), so Spring rolls
back the entire database transaction: no `ledger_transaction` row, no
`ledger_entry` rows, and no balance change on either account, regardless of
how many `persist()`/field-mutation calls had already happened earlier in
the method. `DepositIntegrationTest.databaseOverflowMidTransactionRollsBackTheEntireDeposit`
proves this against a real database-level failure (a `NUMERIC(19,4)`
overflow on the balance `UPDATE`, triggered by pre-seeding an account
balance near the column's precision limit via direct SQL) rather than a
synthetic in-process throw.

## Transaction Boundaries (transfers — not yet implemented)

Transfers are planned to run as a single `@Transactional` service method,
following the same shape as deposit processing above (lock both accounts,
write one transaction header and two balanced entries, update both
balances, roll back fully on any failure) — but between two
`CUSTOMER`/`LIABILITY` accounts instead of a customer account and the
funding account, and with an overdraft check. No such service exists yet.

## Ledger as Source of Truth vs. Materialized Balance (deposit path implemented, Task 4)

`ledger_entry` rows are the authoritative financial record; `account.balance`
is a **materialized balance** column
(`account.balance NUMERIC(19,4) NOT NULL DEFAULT 0`, `CHECK (balance >= 0)`,
since Task 2). For deposits, it is now written only in the same database
transaction as the ledger-entry inserts that justify it —
`DepositService.deposit` never adjusts a balance without also having
written the matching transaction header and two entries in that same
`@Transactional` method. `DepositIntegrationTest` verifies the resulting
balance matches the deposited amount(s) directly via JDBC after each test
scenario. A generalized "recompute every account's balance by summing its
ledger entries and compare" integration test — meaningful once more than
one write path exists — remains for a later task.

## Deterministic Lock Ordering (implemented for deposits, Task 4)

Both deposits and transfers touch two account rows (transfer: sender +
recipient; deposit: customer account + the shared `EXTERNAL_FUNDING`
account). Both rows are locked with `SELECT ... FOR UPDATE`, always in
ascending account-`id` (UUID) order — never by role (not
source-then-destination, not customer-then-funding) — before any validation
or write.

This matters for two distinct reasons:
- **Transfers (not yet implemented):** locking by role instead of a fixed
  order can deadlock when two concurrent transfers run in opposite
  directions (A→B and B→A).
- **Deposits (implemented):** concurrent deposits to *different* customers
  still both write the same shared `EXTERNAL_FUNDING` row; locking only the
  customer row would leave that shared row exposed to a lost update.

`AccountRepository.findByIdAndFundingAccountForUpdate` implements this for
deposits: one JPQL query, `@Lock(PESSIMISTIC_WRITE)`, matching either the
destination account id or the funding-account taxonomy, `ORDER BY a.id` —
a single round trip that locks both rows (or the one row, if the
destination id happens to already be the funding account) in ascending id
order. `DepositIntegrationTest.concurrentDepositsIntoSameWalletDoNotLoseUpdates`
fires 20 concurrent HTTP deposit requests at the same customer account
through real PostgreSQL row locking (no Java-only synchronization, no
mocks) and asserts the final balance equals the starting balance plus the
sum of all 20 deposits, with no lost updates.

## Ledger Immutability (implemented, Task 2)

`ledger_entry` and `ledger_transaction` are both part of the authoritative
financial record. Both have a `BEFORE UPDATE OR DELETE` PostgreSQL trigger
(`trg_ledger_entry_immutable`, `trg_ledger_transaction_immutable`) that
unconditionally rejects mutation — `INSERT` remains allowed. This is a
database-level guard rather than differentiated role grants, because the
project currently uses a single application database role — grants alone
would provide no real protection. Verified directly against PostgreSQL in
`SchemaMigrationIntegrationTest` (`UPDATE`/`DELETE` on either table raise
the trigger's exception) and re-verified in `DepositIntegrationTest`
against rows a real deposit actually created, since deposit processing is
the first code path that writes to these tables. Deposit processing only
ever `INSERT`s into `ledger_transaction`/`ledger_entry` — nothing in
`DepositService` updates or deletes an existing ledger row, and the
triggers remain unmodified.

## Public vs. Internal Account Lookup

**Deposits implement the outcome of this pattern, via a different shape
than originally sketched here.** Rather than two separate repository
methods, `AccountRepository.findByIdAndFundingAccountForUpdate` combines
both lookups into the one locking query described above (it has to — both
rows must be locked together, in id order, before either is read). The
public/internal distinction is then enforced by `DepositService` itself:
the row matching the caller-supplied `destinationAccountId` is only
accepted if its taxonomy is `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` —
otherwise `AccountNotFoundException` is thrown (404), identical to a
nonexistent id, whether the id belongs to `EXTERNAL_FUNDING` or (in a
hypothetical future) any other `SYSTEM` account. The funding account is
never looked up by a client-supplied id at all — it's the *other* row the
same query returns, selected purely by taxonomy and currency.

`GET /api/v1/accounts/{id}` still does not exist. If/when it's added, the
original two-method split (a `findPubliclyVisibleById` used by every
read-only public path, vs. an internal-only lookup) may still be the
better shape for a plain single-row read — the combined locking query
above is specific to needing both rows locked together for a write.

## Money and Currency (implemented)

`NUMERIC(19,4)` in PostgreSQL (never `FLOAT`/`REAL`/`DOUBLE PRECISION`),
present on `account.balance` and `ledger_entry.amount`, mapped to
`BigDecimal` in the `Account` and `LedgerEntry` entities (Tasks 3–4).
`DepositRequest.amount` is validated with `@Digits(integer = 15, fraction = 4)`
— matching `NUMERIC(19,4)`'s precision exactly — so an out-of-range amount
is rejected as a clean 400 rather than a raw SQL numeric-overflow error.
Currency is `VARCHAR(3)`,
constrained to a three-uppercase-letter ISO 4217 shape at the database
level (`chk_account_currency_format`, `chk_ledger_entry_currency_format`).
The stricter "USD only" rule is enforced in `AccountService` (Task 3), not
the database — the schema itself still accepts any ISO-4217-shaped code, by
design, so a future currency is a validation-list change, not a schema
redesign. `AccountService` normalizes accepted lowercase input
(`"usd"` → `"USD"`) before validating; this is a value-preserving
normalization, not a contract change, so it doesn't conflict with
`docs/API_SPEC.md`.
