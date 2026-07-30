# Architecture

> **Status: database layer (Task 2), account creation (Task 3), deposits
> (Task 4), transfers (Task 5), and account balance/transaction-history
> reads (Task 6) implemented; nothing in Phase 1 remains unimplemented
> except plain account lookup by id.** The `account` package has account
> creation, deposit processing, and now read-only account queries; the
> `ledger` package has `LedgerTransaction`, `LedgerEntry`, and their
> repositories/enums; the `transfer` package has transfer processing. The
> `common` package now also holds `PagedResponse<T>` (Task 6), alongside
> the shared exception-handling class from Task 5. `GET
> /api/v1/accounts/{id}` still does not exist — `docs/TASKS.md`'s Task 6
> line scoped that task to balance and history only, not general account
> lookup — so the public-vs-internal lookup split described below is still
> only partially realized (see that section for exactly what deposits,
> transfers, and the new read endpoints do instead).

## Style

LedgerGuard is a modular monolith organized by business capability, not by
technical layer. No microservices.

## Package Structure (Phase 1)

Only these packages are created in Phase 1, each only when the task that
needs it starts — no empty placeholder packages are scaffolded in advance:

- `account` — **account creation (Task 3), deposits (Task 4), and
  balance/history reads (Task 6) implemented**; also holds exceptions
  reused by transfers (`AccountNotFoundException`,
  `CurrencyMismatchException`, `UnsupportedCurrencyException`, plus
  `InsufficientFundsException`/`SameAccountTransferException` from
  Task 5). Plain account lookup by id (`GET /api/v1/accounts/{id}`)
  remains unimplemented — never assigned to any task so far.
- `ledger` — **implemented (Task 4)**: `LedgerTransaction`, `LedgerEntry`,
  `TransactionType`, `TransactionStatus`, `LedgerEntryType`, and their
  repositories. `LedgerEntryRepository` gained one query in Task 6 for
  paginated account history.
- `transfer` — **implemented (Task 5)**: `TransferController`,
  `TransferService`, `TransferRequest`/`TransferResponse` — the transfer
  use case, spanning `account` and `ledger` as planned.
- `common` — **implemented (Tasks 5–6)**: `AccountAndTransferExceptionHandler`
  (Task 5) and `PagedResponse<T>` (Task 6) — see "Error Handling" and
  "Account Balance and Transaction History" below. Not the full
  `ApiExceptionHandler`/`ApiError` envelope described in
  `docs/API_SPEC.md`'s "Error Response Shape", which remains Task 7's
  responsibility.

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

## Transfer Processing (implemented, Task 5)

`TransferController.transfer` (`POST /api/v1/transfers`) →
`TransferService.transfer` → the same `AccountRepository` +
`LedgerTransactionRepository` + `LedgerEntryRepository` that deposit
processing uses. The shape follows deposit processing closely, with two
differences: both accounts are explicit (a source and a destination, both
supplied by the caller — neither is resolved internally by taxonomy like
`EXTERNAL_FUNDING` is), and there's a balance-sufficiency check before any
write. One `@Transactional` service method:

1. Reject if `sourceAccountId == destinationAccountId`
   (`SameAccountTransferException`, 422) — checked first, before any
   database access.
2. Normalize/validate the request currency (must be `USD`).
3. Lock both accounts in one query, in ascending account-id order,
   regardless of which is source and which is destination — see
   "Deterministic Lock Ordering" below.
4. Confirm both locked rows are actually
   `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` (a `SYSTEM` account id, or any
   other taxonomy, is treated as 404 for either side) and that both
   currencies match the request.
5. Confirm `source.balance >= amount` (`InsufficientFundsException`, 422,
   if not) — checked before any ledger or balance write.
6. Insert one `LedgerTransaction` (`TRANSFER`/`COMPLETED`).
7. Insert two `LedgerEntry` rows: `DEBIT` the source, `CREDIT` the
   destination, both for the same amount and currency, both referencing
   the transaction just inserted.
8. Decrease the source's materialized `balance` and increase the
   destination's, in place (plain field mutation on the already-locked,
   already-managed `Account` entities).
9. Flush explicitly, then refresh the transaction entity to reliably pick
   up `created_at` — see "Transaction Boundary and Rollback" under Deposit
   Processing above; the same rationale and the same rollback guarantee
   apply here unchanged (nothing catches and swallows an exception, so any
   failure at any step rolls back the whole operation).
   `TransferIntegrationTest.databaseOverflowMidTransactionRollsBackTheEntireTransfer`
   re-proves this against a real `NUMERIC(19,4)` overflow, the same
   technique used for deposits.

`TransferRequest` has fields for `sourceAccountId`, `destinationAccountId`,
`amount`, and `currency` only — there is no field for transaction id/type/
status, entry direction, ledger-entry ids, balances, timestamps, or account
taxonomy. `EXTERNAL_FUNDING` is never touched by a transfer — the two
locked accounts are exactly the caller-supplied source and destination, and
`TransferIntegrationTest.depositFollowedByTransferProducesExpectedBalances`
asserts the funding balance is unchanged by a transfer that follows a
deposit.

The debit/credit direction reasoning is the mirror image of a deposit's:
customer wallets are **liability** accounts, where `balance = credits −
debits`. A transfer decreases what the platform owes the source customer
(liability decreases → **DEBIT**) and increases what it owes the
destination customer (liability increases → **CREDIT**) — both entries
still sum to the same amount, so the transaction stays balanced, and unlike
a deposit, the *combined* balance of the two accounts is unchanged (money
moves internally; none enters or leaves the ledger).

## Account Balance and Transaction History (implemented, Task 6)

`AccountController.getBalance`/`getTransactionHistory`
(`GET /api/v1/accounts/{id}/balance`, `GET /api/v1/accounts/{id}/transactions`)
→ `AccountQueryService` → `AccountRepository` + `LedgerEntryRepository`.
Both methods are `@Transactional(readOnly = true)` and use plain
`findById`/`findBy...` reads — **no `@Lock(PESSIMISTIC_WRITE)`, unlike
every write path in Tasks 4–5**. A read needs no lock: PostgreSQL's
read-committed isolation guarantees a `GET` only ever sees fully committed
rows, and every prior write (Task 4/5) already updated the ledger and the
materialized balance together, atomically, before its own transaction
committed — so by the time a `GET` can see a balance change at all, the
ledger entries that justify it are already committed too. There is nothing
for a read-side lock to protect against.

- **Balance:** `getBalance` resolves the account (404 if missing or not a
  `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` row — the same taxonomy check
  `DepositService`/`TransferService` already use, duplicated here as a
  small private method rather than extracted into a shared abstraction,
  consistent with how each write service already has its own copy) and
  returns its `balance` field exactly as stored — no summing, no
  recomputation, no caching. A `GET` never constructs a `LedgerTransaction`
  or `LedgerEntry`, and never mutates the `Account` entity it reads.
- **Transaction history:** `getTransactionHistory` performs the same
  existence/taxonomy check, then calls
  `LedgerEntryRepository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, PageRequest.of(page, size))`.
  This is a derived Spring Data query — Spring Data JPA issues exactly two
  SQL statements per call (the page's content via `LIMIT`/`OFFSET`, and a
  `COUNT` for `totalElements`/`totalPages`), never one query per row and
  never the account's entire history. The existing
  `idx_ledger_entry_account_id(account_id, created_at)` index from Task 2
  supports the `account_id` filter and `created_at` ordering directly
  (Postgres can walk a B-tree backwards for `DESC`, so no separate sort
  step is needed for the primary key; only the rare exact-timestamp tie
  needs an in-memory comparison against `id`). Because the query filters by
  `account_id`, an account only ever sees its own entries — a deposit's
  `EXTERNAL_FUNDING` `DEBIT` entry belongs to a different `account_id` and
  is structurally excluded, not filtered out after the fact; likewise a
  transfer's two entries belong to two different accounts, so each
  account's history shows only its own one entry from that transaction,
  never the counterparty's.
- **Ordering and pagination:** the ordering (`created_at DESC, id DESC`)
  and the pagination contract (`page`/`size` defaults and bounds, the
  custom `PagedResponse` envelope) were both genuinely underspecified in
  the original `docs/API_SPEC.md` stub inherited from Task 1's planning
  pass, and were confirmed with the user before implementation rather than
  invented — see `docs/API_SPEC.md`'s Task 6 sections for the now-approved
  contract. `page`/`size` are validated with `@Min`/`@Max` on the
  controller's `@RequestParam`s (`@Validated` at the class level); this
  triggers Bean Validation's method-interceptor path, which throws
  `jakarta.validation.ConstraintViolationException` directly (not the
  newer web-aware exception Spring MVC's own defaults already map to 400)
  — so `common.AccountAndTransferExceptionHandler` gained one more mapping
  in Task 6 for that specific exception type, still 400, still minimal.
  `PagedResponse<T>` (in `common`) is a plain generic record, not Spring
  Data's own `Page` type — the response body never leaks Spring Data's
  default JSON shape, per the approved contract.

## Ledger as Source of Truth vs. Materialized Balance (implemented, Tasks 4–5)

`ledger_entry` rows are the authoritative financial record; `account.balance`
is a **materialized balance** column
(`account.balance NUMERIC(19,4) NOT NULL DEFAULT 0`, `CHECK (balance >= 0)`,
since Task 2). For both deposits and transfers, it is written only in the
same database transaction as the ledger-entry inserts that justify it —
neither `DepositService` nor `TransferService` ever adjusts a balance
without also having written the matching transaction header and two
entries in that same `@Transactional` method.
`DepositIntegrationTest`/`TransferIntegrationTest` verify the resulting
balances directly via JDBC after each test scenario, including that a
transfer's combined source+destination balance is unchanged — the closest
thing Phase 1 has so far to a conservation-of-funds check. A generalized
"recompute every account's balance by summing its ledger entries and
compare against the materialized value" integration test remains for a
later task.

## Deterministic Lock Ordering (implemented, Tasks 4–5)

Both deposits and transfers touch two account rows (transfer: source +
destination; deposit: customer account + the shared `EXTERNAL_FUNDING`
account). Both rows are locked with `SELECT ... FOR UPDATE`, always in
ascending account-`id` (UUID) order — never by role (not
source-then-destination, not customer-then-funding) — before any validation
or write.

This matters for two distinct reasons:
- **Transfers:** locking by role instead of a fixed order can deadlock
  when two concurrent transfers run in opposite directions (A→B and B→A) —
  each would lock its own "source" first, and each other's "source" is the
  other's "destination", a classic circular wait. Locking by id order
  instead means both transactions always attempt to acquire the *same*
  first lock, so one simply waits for the other rather than each holding
  what the other needs.
- **Deposits:** concurrent deposits to *different* customers still both
  write the same shared `EXTERNAL_FUNDING` row; locking only the customer
  row would leave that shared row exposed to a lost update.

Two repository methods implement this, for the two different shapes of
"which two rows":
- `AccountRepository.findByIdAndFundingAccountForUpdate` (deposits): one
  known id (the destination) plus one row resolved by taxonomy (the
  funding account) — `ORDER BY a.id` locks whichever of the two has the
  smaller id first, matching either condition in one query.
- `AccountRepository.findByIdsForUpdate` (transfers, Task 5): two known ids
  passed in — `WHERE a.id IN :ids ORDER BY a.id`. `TransferService` passes
  `List.of(sourceAccountId, destinationAccountId)` in *request* order, but
  the lock acquisition order is governed entirely by the query's
  `ORDER BY`, not by the order the ids were passed in — so which account is
  semantically the "source" never affects which row is locked first.

`DepositIntegrationTest.concurrentDepositsIntoSameWalletDoNotLoseUpdates`
fires 20 concurrent HTTP deposit requests at the same customer account;
`TransferIntegrationTest.concurrentTransfersFromOneSourceDoNotOverspend`
fires 20 concurrent transfer requests from one funded source (only 10 can
be afforded, and the test asserts exactly 10 succeed and exactly 10 are
rejected with 422, never a negative balance);
`TransferIntegrationTest.concurrentOppositeDirectionTransfersCompleteWithoutLostUpdatesOrDeadlock`
fires 10 A→B and 10 B→A transfers concurrently between the same two
accounts and asserts every one of the 20 completes (none times out, none
deadlocks) and both balances return to their starting values. All three
run against real PostgreSQL row locking — no Java-only synchronization, no
mocks.

## Ledger Immutability (implemented, Task 2)

`ledger_entry` and `ledger_transaction` are both part of the authoritative
financial record. Both have a `BEFORE UPDATE OR DELETE` PostgreSQL trigger
(`trg_ledger_entry_immutable`, `trg_ledger_transaction_immutable`) that
unconditionally rejects mutation — `INSERT` remains allowed. This is a
database-level guard rather than differentiated role grants, because the
project currently uses a single application database role — grants alone
would provide no real protection. Verified directly against PostgreSQL in
`SchemaMigrationIntegrationTest` (`UPDATE`/`DELETE` on either table raise
the trigger's exception), and re-verified in both
`DepositIntegrationTest` and `TransferIntegrationTest` against rows a real
deposit/transfer actually created. Both `DepositService` and
`TransferService` only ever `INSERT` into
`ledger_transaction`/`ledger_entry` — neither updates or deletes an
existing ledger row (a transfer never touches a deposit's rows, or vice
versa), and the triggers remain unmodified.

## Public vs. Internal Account Lookup

**Deposits and transfers both implement the outcome of this pattern, via a
different shape than originally sketched here.** Rather than a separate
"public-lookup" repository method, both `findByIdAndFundingAccountForUpdate`
(deposits) and `findByIdsForUpdate` (transfers) combine locking and lookup
into one query (they have to — the rows must be locked together, in id
order, before either is read). The public/internal distinction is then
enforced in the service layer: every caller-supplied account id
(`destinationAccountId` for deposits; both `sourceAccountId` and
`destinationAccountId` for transfers) is only accepted if its resolved
taxonomy is `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` — otherwise
`AccountNotFoundException` is thrown (404), identical to a nonexistent id,
whether the id belongs to `EXTERNAL_FUNDING` or (in a hypothetical future)
any other `SYSTEM` account. Deposits' funding account is never looked up
by a client-supplied id at all — it's the *other* row the same query
returns, selected purely by taxonomy and currency; transfers never touch
`EXTERNAL_FUNDING` in the first place.

`AccountQueryService` (Task 6) confirms the prediction this section made:
for a plain single-row read (no locking needed, see "Account Balance and
Transaction History" above), the shape really is closer to the originally
sketched "public-lookup" method — `AccountQueryService.resolveCustomerWallet`
uses plain `AccountRepository.findById` (already inherited from
`JpaRepository`, no new repository method needed) plus the same taxonomy
check pattern, rather than anything resembling the write paths' combined
locking queries. `GET /api/v1/accounts/{id}` itself still does not exist —
`docs/TASKS.md`'s Task 6 line never included it — but if/when it's added,
it can reuse this same `findById` + taxonomy-check shape directly.

## Money and Currency (implemented)

`NUMERIC(19,4)` in PostgreSQL (never `FLOAT`/`REAL`/`DOUBLE PRECISION`),
present on `account.balance` and `ledger_entry.amount`, mapped to
`BigDecimal` in the `Account` and `LedgerEntry` entities (Tasks 3–4).
`AccountBalanceResponse.balance` and `TransactionHistoryItem.amount`
(Task 6) both carry that same `BigDecimal` straight through from the
entity to the response — read, never recomputed, never rounded to a
different scale.
`DepositRequest.amount` and `TransferRequest.amount` are both validated
with `@Digits(integer = 15, fraction = 4)` — matching `NUMERIC(19,4)`'s
precision exactly — so an out-of-range amount is rejected as a clean 400
rather than a raw SQL numeric-overflow error. Currency is `VARCHAR(3)`,
constrained to a three-uppercase-letter ISO 4217 shape at the database
level (`chk_account_currency_format`, `chk_ledger_entry_currency_format`).
The stricter "USD only" rule is enforced in `AccountService`/
`DepositService`/`TransferService`, not the database — the schema itself
still accepts any ISO-4217-shaped code, by design, so a future currency is
a validation-list change, not a schema redesign. All three services
normalize accepted lowercase input (`"usd"` → `"USD"`) before validating;
this is a value-preserving normalization, not a contract change, so it
doesn't conflict with `docs/API_SPEC.md`.

## Error Handling (implemented, Tasks 5–6)

`common.AccountAndTransferExceptionHandler` (`@RestControllerAdvice`) maps
`UnsupportedCurrencyException`/`CurrencyMismatchException`/
`InsufficientFundsException`/`SameAccountTransferException` to 422,
`AccountNotFoundException` to 404, and (since Task 6)
`jakarta.validation.ConstraintViolationException` to 400 — for every
controller in the application. This replaces the two `@ExceptionHandler`
methods that used to live directly on `AccountController` (added in
Task 3, extended in Task 4) — moved here in Task 5 once
`TransferController` needed to map the same exception types and
copy-pasting the same handler methods into a second controller was the
alternative. This was a behavior-preserving refactor: response status
codes and bodies were unchanged, and
`AccountCreationIntegrationTest`/`DepositIntegrationTest` (which assert on
exactly these statuses) still passed without modification.

The `ConstraintViolationException` mapping was added in Task 6 because
`@Validated`-driven method-parameter constraints (the transaction-history
endpoint's `@Min`/`@Max` on `page`/`size`) go through Bean Validation's AOP
method interceptor, which throws that JSR-380 exception type directly —
distinct from `MethodArgumentNotValidException` (thrown for `@Valid
@RequestBody` failures, e.g. deposit/transfer amount validation) and from
`MethodArgumentTypeMismatchException` (thrown for a malformed path
variable, e.g. a non-UUID `{id}`), both of which Spring MVC already maps
to 400 automatically without any handler here. Without the added mapping,
an out-of-range `page`/`size` would have surfaced as an unmapped 500.

Bean-validation failures on request bodies and unknown-JSON-property
rejections are still left to Spring's own default exception resolution
(400), unchanged since Task 3. This is still not the complete Task 7
framework: there is no unified `{timestamp, status, error, message, path}`
envelope yet (see `docs/API_SPEC.md`'s "Error Response Shape") — just a
consistent, minimal `{message}` body and the correct status code.
