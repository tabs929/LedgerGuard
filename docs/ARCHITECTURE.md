# Architecture

> **Status: database layer (Task 2) and account creation (Task 3)
> implemented; the rest is still planning.** The `account` **Java package**
> now exists, but only for account creation: `Account` entity,
> `AccountCategory`/`AccountClass`/`AccountPurpose` enums, `AccountRepository`,
> `AccountService`, `AccountController`, and request/response DTOs. It does
> **not** yet implement the public-vs-internal lookup split described below
> (there is no `GET /api/v1/accounts/{id}` yet, so nothing reads a `SYSTEM`
> account through a public path to guard against). The `ledger` and
> `transfer` packages still do not exist. Transaction boundaries,
> deterministic lock ordering, and the ledger-as-source-of-truth write path
> remain future work (Task 4+) — Task 3 only ever writes to `account`, never
> to `ledger_transaction`/`ledger_entry`.

## Style

LedgerGuard is a modular monolith organized by business capability, not by
technical layer. No microservices.

## Package Structure (Phase 1)

Only these packages are created in Phase 1, each only when the task that
needs it starts — no empty placeholder packages are scaffolded in advance:

- `account` — **account creation implemented (Task 3)**; lookup and the
  public/internal repository split described below remain unimplemented.
- `ledger` — `LedgerTransaction` and `LedgerEntry` persistence.
- `transfer` — the transfer use case (spans `account` and `ledger`).
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

## Transaction Boundaries (deposits/transfers — not yet implemented)

Each business operation (deposit, transfer) is planned to run as a single
`@Transactional` service method that performs all of its ledger-entry
inserts and balance updates together, rolling back fully on any failure. No
such service exists yet — Task 3 only ever writes to `account`, never to
`ledger_transaction`/`ledger_entry`.

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

`AccountRepository` today is a plain `JpaRepository<Account, UUID>` with no
custom lookup methods — Task 3 has no endpoint that looks accounts up by
id, so there is nothing yet that could leak a `SYSTEM` account's existence.
The public/internal split below applies starting with `GET
/api/v1/accounts/{id}` and any endpoint that resolves an id supplied by a
caller:
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

## Money and Currency (implemented)

`NUMERIC(19,4)` in PostgreSQL (never `FLOAT`/`REAL`/`DOUBLE PRECISION`),
present on `account.balance` and `ledger_entry.amount`, mapped to
`BigDecimal` in the `Account` entity (Task 3). Currency is `VARCHAR(3)`,
constrained to a three-uppercase-letter ISO 4217 shape at the database
level (`chk_account_currency_format`, `chk_ledger_entry_currency_format`).
The stricter "USD only" rule is enforced in `AccountService` (Task 3), not
the database — the schema itself still accepts any ISO-4217-shaped code, by
design, so a future currency is a validation-list change, not a schema
redesign. `AccountService` normalizes accepted lowercase input
(`"usd"` → `"USD"`) before validating; this is a value-preserving
normalization, not a contract change, so it doesn't conflict with
`docs/API_SPEC.md`.
