# Architecture

> **Status: Phase 1 complete (Tasks 1–9). Phase 2, Tasks 10 (idempotency),
> 11 (transactional outbox), and 12 (Kafka publishing) implemented.**
> Database layer (Task 2), account creation (Task 3), deposits (Task 4),
> transfers (Task 5), account balance/transaction-history reads (Task 6),
> global error handling (Task 7), OpenAPI documentation (Task 8), CI
> (Task 9), idempotency for deposits/transfers (Task 10), the transactional
> outbox (Task 11), and publishing pending outbox events to Kafka
> (Task 12) are all implemented; only plain account lookup by id remains
> unimplemented — no task has ever been assigned it. The `account` package
> has account creation, deposit processing, and read-only account queries;
> the `ledger` package has `LedgerTransaction`, `LedgerEntry`, and their
> repositories/enums; the `transfer` package has transfer processing; the
> `idempotency` package (Task 10) has the idempotency key record,
> repository, command, service, and conflict exception — see "Idempotency"
> below; the `outbox` package (Task 11) has the outbox event record,
> repository, event-type enums, the two version-1 event payload records,
> and the event factory, and (Task 12) now also the publisher scheduler,
> the per-event transactional publisher, the Kafka topic configuration,
> and validated publisher properties — see "Transactional Outbox" and
> "Kafka Publishing" below. The `common` package holds `PagedResponse<T>`
> (Task 6), `ApiError` and `GlobalExceptionHandler` (Task 7), and
> `OpenApiConfig` (Task 8 — see "API Documentation" below). Task 9 added
> `.github/workflows/ci.yml` only — no application code, no new package,
> no behavior change (see "Continuous Integration" at the end of this
> document). `GET /api/v1/accounts/{id}` still does not exist, so the
> public-vs-internal lookup split described below is still only partially
> realized (see that section for exactly what deposits, transfers, and the
> read endpoints do instead). A Kafka consumer, settlement/reconciliation,
> and authentication remain unimplemented — see `docs/TASKS.md` for what
> Tasks 13+ still cover; Task 12 provides **at-least-once** publication
> only, never exactly-once, and adds no consumer or business reaction to
> an event.

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
- `common` — **implemented (Tasks 5–8)**: `PagedResponse<T>` (Task 6),
  `ApiError` and `GlobalExceptionHandler` (Task 7, superseding the
  narrower `AccountAndTransferExceptionHandler` introduced in Task 5), and
  `OpenApiConfig` (Task 8) — see "Error Handling", "Account Balance and
  Transaction History", and "API Documentation" below.
  `docs/API_SPEC.md`'s "Error Response Shape" and "OpenAPI/Swagger"
  sections are now both fully implemented, not just planned.

- `idempotency` — **implemented (Task 10)**: `IdempotencyKeyRecord` (JPA
  entity mapping the `idempotency_key` table) and `IdempotencyKeyRepository`,
  `IdempotencyOperationType`, `IdempotencyCommand` (the canonical,
  normalized deposit/transfer command used for conflict/replay comparison),
  `IdempotencyService` (the claim/replay/conflict orchestration, called
  from inside `DepositService`/`TransferService`), and
  `IdempotencyConflictException` — see "Idempotency" below.

- `outbox` — **implemented (Tasks 11–12)**: `OutboxAggregateType`/
  `OutboxEventType` enums, `OutboxEvent` (JPA entity mapping the
  `outbox_event` table) and `OutboxEventRepository`,
  `DepositCompletedEvent`/`TransferCompletedEvent` (the version-1 event
  envelope records), and `OutboxEventFactory` (the single insertion point
  `DepositService`/`TransferService` call) from Task 11; `OutboxPublisherProperties`
  (validated configuration), `OutboxKafkaTopicConfig` (the managed Kafka
  topic), `OutboxPublisherScheduler` (candidate polling), and
  `OutboxPublisher` (the per-event transactional publish) from Task 12 —
  see "Transactional Outbox" and "Kafka Publishing" below.

Packages named in `CLAUDE.md` for later phases (`settlement`,
`reconciliation`, `security`, `audit`) are **not yet created** —
`idempotency` (Task 10) and `outbox` (Task 11) are the two Phase 2/3
packages added so far.

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

## Idempotency (implemented, Task 10)

`POST /api/v1/accounts/{id}/deposits` and `POST /api/v1/transfers` both
require an `Idempotency-Key` header (`@RequestHeader` + `@Pattern` on the
controller method parameter, validated the same way `page`/`size` already
are — a missing header is a new `MissingRequestHeaderException` mapping in
`GlobalExceptionHandler`, an invalid one is the pre-existing
`ConstraintViolationException` mapping, both 400). Controllers stay thin:
they receive and validate the header, then pass it straight to
`DepositService.deposit`/`TransferService.transfer`, which now take an
extra `String idempotencyKey` parameter alongside the existing request DTO.

**Canonical command.** Before any locking or validation, both services
normalize the request exactly as they already did (currency uppercased,
amount scaled to 4 decimal places) and build an `IdempotencyCommand`
(operation type, account id(s), amount, currency) from that normalized
data — this is what makes `"100"`, `"100.0"`, and `"100.00"` compare as
the same command regardless of how the client formatted the request, and
is why the currency/amount normalization lines moved to the top of each
method rather than staying inline further down (a pure reordering of
side-effect-free computation — the actual validation/throw order inside
each service's private `doDeposit`/`doTransfer` method is unchanged from
before Task 10).

**`IdempotencyService.execute(key, command, responseType, transactionIdExtractor, operation)`**
is the single reusable orchestration point both services call — it does
not duplicate deposit or transfer logic, it only wraps a `Supplier<T>` that
the caller provides:

1. Acquire a PostgreSQL **transaction-scoped advisory lock**
   (`pg_advisory_xact_lock(bigint)`), keyed on the first 8 bytes of
   `SHA-256(idempotencyKey)` as a signed `long`. This happens before
   `idempotency_key` or any account row is touched.
2. Look up an existing `idempotency_key` row for that key (plain `SELECT`
   — no `FOR UPDATE` needed, since the advisory lock above already
   serializes every request bearing this exact key).
   - **Found, canonical match** (`IdempotencyKeyRecord.matches`, an exact
     comparison of operation type, account id(s), amount via
     `BigDecimal.compareTo`, and currency — never relying on the stored
     `command_hash` alone) → deserialize and return the stored
     `response_body`/`response_status` verbatim. No new `LedgerTransaction`,
     no new `LedgerEntry`, no balance change, no new `idempotency_key` row.
   - **Found, mismatch** (different amount, currency, account, or
     operation type — including a deposit key reused against `/transfers`
     or vice versa) → throw `IdempotencyConflictException` immediately.
     No financial work is attempted.
   - **Not found** → run the supplied deposit/transfer operation (the
     existing account-locking, ledger-entry, and balance-update logic,
     completely unchanged), then, as the **last statement** of the same
     `@Transactional` method, persist a new `IdempotencyKeyRecord`
     (including the just-created `ledger_transaction.id` and the
     JSON-serialized response) and flush.

**Why this is atomic and why a failed attempt never consumes the key.**
Everything above — the advisory lock, the lookup, the financial write, and
the final `idempotency_key` insert — runs inside the one ambient
`@Transactional` method (`DepositService.deposit`/`TransferService.transfer`,
default `REQUIRES` propagation); nothing opens a `REQUIRES_NEW` transaction,
and nothing catches and swallows an exception to commit partial state. If
the wrapped operation throws for any reason — validation, `404`/`422`
domain exceptions, or a genuine database-level failure surfaced at the
existing explicit flush — the exception propagates out of the whole method
uninterrupted, so Spring rolls back the entire transaction: no
`ledger_transaction`, no `ledger_entry` rows, no balance change, **and no
`idempotency_key` row**, since that insert is never reached. The key is
therefore free to be retried, including with corrected data, exactly as if
it had never been used.

**Why the concurrency mechanism is correct and PostgreSQL-native, not
in-memory.** `pg_advisory_xact_lock` is a real PostgreSQL server-side lock,
scoped to the calling transaction and released automatically on that
transaction's commit **or** rollback — never left held, never requiring
explicit release code. A second request bearing the *same* key computes
the identical lock id (the SHA-256 truncation is deterministic) and blocks
inside that same call until the first transaction ends:
- If the first transaction **commits**, the second unblocks and its
  `SELECT` now sees the just-committed `idempotency_key` row (PostgreSQL's
  read-committed isolation guarantees this) — it takes the replay or
  conflict branch, never the "not found" branch, so it can never redo the
  financial write.
- If the first transaction **rolls back**, the second unblocks and finds
  no row — it becomes the new "first" attempt for that key and proceeds
  normally.

Because this lock lives in PostgreSQL itself rather than the JVM, it is
correct across multiple application instances without any inter-instance
coordination — the same guarantee every other write-path invariant in this
project already relies on (PostgreSQL, not application code, as the source
of truth for concurrent correctness). The `UNIQUE` constraint on
`idempotency_key.idempotency_key` remains as a defense-in-depth backstop,
but in practice the advisory lock is what prevents the race — two
transactions can never simultaneously be in the "not found → do the work"
branch for the same key. A hash collision between two *different* keys
would only cause them to serialize unnecessarily against each other
(performance only) — correctness never depends on the lock id alone, since
the exact key string is always compared afterward via the `idempotency_key`
column, and the canonical command comparison never relies on
`command_hash` alone either.

**Why this composes safely with the existing deterministic account-row
locking.** The advisory lock is always acquired *before* either write
service takes its `SELECT ... FOR UPDATE` account-row lock(s) (see
"Deterministic Lock Ordering" above), in every code path that uses it — so
it is always the outer lock, never interleaved with or acquired after the
account locks. Two requests bearing the same key are fully serialized by
the advisory lock before either can reach account locking, so they never
contend on account rows concurrently under that key; two requests bearing
*different* keys use independent, uncorrelated lock ids, so they never
serialize against each other and the pre-existing ascending-account-id
ordering between them is completely unaffected. No new deadlock cycle is
possible between the two lock types.

**Persistence.** `idempotency_key` (Flyway `V2__add_idempotency_key.sql`
— `V1` is unmodified) stores the key (`UNIQUE`, format-checked), the
canonical command (operation type, primary/secondary account id, amount,
currency), a SHA-256 `command_hash` (defense-in-depth only — see above),
the resulting `ledger_transaction_id` (`NOT NULL`, foreign key), and the
`response_status`/`response_body` needed for exact replay. Retention is
indefinite in Phase 2 — no TTL column, no cleanup job, no delete endpoint;
see `docs/DATA_MODEL.md` for the full schema and `docs/API_SPEC.md` for
the client-facing contract.

## Transactional Outbox (implemented, Task 11)

Every newly committed deposit or transfer durably records exactly one
domain event — `DEPOSIT_COMPLETED` or `TRANSFER_COMPLETED` — in
`outbox_event`, in the same PostgreSQL transaction as the
`ledger_transaction`, its two `ledger_entry` rows, both accounts'
materialized balance updates, and the Task 10 `idempotency_key` row. This
is persistence only: nothing in this project reads `outbox_event` yet — no
publisher, no consumer, no Kafka dependency of any kind. `outbox_event`
exists precisely so a later task can poll it and publish without ever
risking the classic dual-write problem (a financial write succeeding while
the corresponding event write fails, or vice versa).

**Insertion point.** `OutboxEventFactory.recordDepositCompleted`/
`recordTransferCompleted` is called from inside the private
`doDeposit`/`doTransfer` methods, immediately after
`entityManager.refresh(transaction)` — the same point that already made
`transaction.getId()`/`transaction.getCreatedAt()` available for the
response DTO (see "Deposit Processing"/"Transfer Processing" above) — and
before either method returns. This is still inside the one ambient
`@Transactional` method (`DepositService.deposit`/`TransferService.transfer`,
default `REQUIRES` propagation, the same method `IdempotencyService.execute`
is itself called from); nothing here opens a `REQUIRES_NEW` transaction,
registers an after-commit callback, or publishes anything asynchronously.
`OutboxEventFactory` calls `repository.save(...)` then `repository.flush()`
immediately, so a constraint violation on `outbox_event` surfaces here,
inside the transaction, exactly like the existing explicit-flush pattern
`DepositService`/`TransferService` already use for their own ledger writes.

**Why this is atomic with the financial write and the idempotency
record.** If anything after this point throws — or if the outbox insert
itself fails its flush — the exception propagates out of the whole
`@Transactional` method uninterrupted (nothing catches and suppresses it),
so Spring rolls back everything: the `ledger_transaction`, both
`ledger_entry` rows, both balance updates, the `outbox_event` row, and
(since the outbox insert happens before `IdempotencyService`'s own final
`save()+flush()` of the `idempotency_key` row, which only runs after the
whole `operation.get()` supplier — including this outbox insert — returns
successfully) the `idempotency_key` row as well. There is exactly one
commit point for the entire operation; nothing here can commit
independently of anything else.

**Why a Task 10 replay or conflict never creates a duplicate event.**
`OutboxEventFactory` is only ever called from inside `doDeposit`/
`doTransfer`, which are only ever reached via the `Supplier<T> operation`
argument to `IdempotencyService.execute` — and that supplier is only
invoked on the "no existing row found" branch (see "Idempotency" above).
A replay (canonical match) returns the stored response directly, without
ever calling the supplier; a conflict (mismatch) throws before the
supplier is ever considered. Structurally, there is no code path from a
replay or a conflict into `OutboxEventFactory` — this required no special
handling in `IdempotencyService` itself, since it falls straight out of
where Task 11's insertion point sits. `uq_outbox_event_identity`
(`UNIQUE (aggregate_type, aggregate_id, event_type)`, Flyway
`V3__add_transactional_outbox.sql`) is a database-level backstop for the
same guarantee, exercised directly (a raw duplicate `INSERT` against an
existing transaction id is rejected) by `OutboxIntegrationTest`.

**Event envelope and payload.** Each `outbox_event.payload` is a
version-1 JSON object serialized from an explicit typed record —
`DepositCompletedEvent` or `TransferCompletedEvent` — never a JPA entity,
a request DTO, a response DTO, or an untyped map. `eventId` is a fresh
`UUID.randomUUID()` generated once per event (never derived from a hash,
a timestamp, or any mutable value) and is also the entity's own `id`
(explicitly assigned in Java, not database-generated, so the payload's
`eventId` and the row's `id` are guaranteed identical by construction, not
by a later lookup). `amount` is pre-formatted via `BigDecimal.toPlainString()`
on the already-normalized, already-4-decimal-scaled amount `DepositService`/
`TransferService` compute for the ledger write itself — a JSON string like
`"100.0000"`, never a bare JSON number, regardless of how the app's
Jackson `ObjectMapper` might otherwise serialize a raw `BigDecimal` field.
`occurredAt` is similarly pre-formatted with `DateTimeFormatter.ISO_INSTANT`
against the exact same `transaction.getCreatedAt()` `Instant` stored in
the row's own `occurred_at` column — not an independently generated
application timestamp. Both choices make the wire format deterministic and
locale-independent, per the Task 11 contract, rather than depending on
Jackson's default `BigDecimal`/`Instant` handling. Serialization uses the
application's actual `tools.jackson.databind.ObjectMapper` bean (Jackson
3 — see the note on this project's Jackson stack in the "Idempotency"
section above); a serialization failure is not caught — it propagates
like any other exception in `doDeposit`/`doTransfer`, triggering the same
whole-operation rollback described above. The deposit payload never
includes the internal `SYSTEM`/`EXTERNAL_FUNDING` account id — only the
public destination account id the caller already knows.

**Immutability.** `outbox_event` rows are never updated or deleted by
application code — `OutboxEvent` exposes no setters. Two triggers in
`V3__add_transactional_outbox.sql` enforce this at the database level
too: `trg_outbox_event_no_delete` unconditionally rejects `DELETE`;
`trg_outbox_event_immutable` rejects any `UPDATE` that changes `id`,
`aggregate_type`, `aggregate_id`, `event_type`, `schema_version`,
`payload`, `occurred_at`, or `created_at`, and separately rejects any
attempt to change `published_at` once it is already non-null — the only
transition ever permitted is `published_at` moving from `NULL` to a
non-null value, exactly once, for a future publisher to claim a row with.
`OutboxIntegrationTest` proves each of these directly: `DELETE` is
rejected, an `UPDATE` of `event_type` or `payload` is rejected, setting
`published_at` from `NULL` to `now()` succeeds, and a further `UPDATE` of
`published_at` (to `NULL` or to a new timestamp) is rejected.

**`published_at` and the publisher boundary.** `published_at` is `NULL`
for every row until Task 12's publisher successfully sends it — nothing
in Task 11 itself ever sets it. `idx_outbox_event_pending` (a partial
index, `WHERE published_at IS NULL`, ordered `(created_at, id)` for a
deterministic, oldest-first scan) exists precisely for that publisher's
poll — see "Kafka Publishing" below for how Task 12 uses it. Task 11
itself still adds no attempt counts, retry timestamps, Kafka offsets,
consumer state, or broker/partition/topic metadata — Task 12 doesn't add
any of those either (see below); this remains deliberately minimal retry
state (`published_at` `NULL` vs. non-null only).

## Kafka Publishing (implemented, Task 12)

Publishes pending `outbox_event` rows (Task 11) to Kafka — durable,
at-least-once persistence becomes durable, at-least-once *delivery*. This
section covers only publication; nothing in this codebase consumes a
published event yet (see `docs/TASKS.md`'s Task 13 entry).

### Topic and record contract

One topic, `ledger.transaction-events.v1` (configurable via
`ledgerguard.outbox.publisher.topic`), managed by the application itself
via a Spring Kafka `NewTopic` bean (`outbox.OutboxKafkaTopicConfig`) —
never relying on broker auto-creation, so partition count and replication
factor are explicit and reviewable rather than whatever a broker's
defaults happen to be. Defaults: 3 partitions, replication factor 1 (a
local/Testcontainers-only value — a real deployment should normally use a
replication factor greater than 1, since 1 means no broker redundancy at
all).

Each Kafka record:
- **key** — `outbox_event.aggregate_id` (the ledger transaction id) as a
  standard UUID string. Every event for the same transaction — in
  practice exactly one, per Task 11's `uq_outbox_event_identity` — lands
  on the same partition, so a future consumer processing one partition at
  a time never sees that transaction's events out of order relative to
  each other.
- **value** — `outbox_event.payload` *exactly as stored*, read straight
  off the entity's `payload` field and handed to `KafkaTemplate.send(...)`
  unchanged. Never deserialized and reserialized, never reconstructed
  from `LedgerTransaction`/`LedgerEntry`, a request DTO, a response DTO,
  or a fresh map — the Kafka value is provably identical to what
  `OutboxIntegrationTest` (Task 11) already proved matches the version-1
  envelope schema.
- Both UTF-8 strings (`StringSerializer` for key and value —
  `spring.kafka.producer.key-serializer`/`value-serializer` in
  `application.yml`).
- **No custom headers.** The key and JSON value are sufficient; nothing
  is duplicated into a header, and in particular the raw `Idempotency-Key`
  is never present anywhere in the key, value, or headers — it was never
  part of the stored payload to begin with (see "Transactional Outbox"
  above), and `OutboxPublisherIntegrationTest` asserts its absence
  directly against a real consumed record.

### Producer configuration

`spring.kafka.producer`: `acks: all` (the broker only acknowledges after
every in-sync replica has the record — the strongest durability the
protocol offers) and `properties.enable.idempotence: true` (suppresses
duplicate records from the *producer's own* broker-level retries within
one send). Neither setting, together or apart, is end-to-end
exactly-once delivery — see "At-least-once and the acknowledgement
window" below for exactly why.

### Publisher activation and scheduling

`ledgerguard.outbox.publisher.*` (`OutboxPublisherProperties`, Jakarta
Bean Validation-checked at startup — a blank topic, or a non-positive
partition count/replication factor/poll delay/batch size/send timeout,
fails application startup clearly rather than behaving unpredictably
later): `enabled` (default `true`), `topic`, `partitions`,
`replication-factor`, `poll-delay-millis`, `batch-size`,
`send-timeout-millis`.

`OutboxPublisherScheduler` (`@Scheduled(fixedDelayString = "${ledgerguard.outbox.publisher.poll-delay-millis}")`,
`@EnableScheduling` added to `LedgerGuardApplication`) reads a bounded,
deterministic batch of pending event **ids only** — never payloads —
via `OutboxEventRepository.findPendingCandidateIds`, ordered
`created_at ASC, id ASC` and backed directly by `idx_outbox_event_pending`,
capped at `batch-size` (`LIMIT`, so a large backlog is never loaded
entirely into memory). It then hands each candidate id, one at a time, to
`OutboxPublisher.publishIfPending`, catching and logging any single
candidate's failure so every later candidate in the same pass still gets
its own attempt — one bad event never blocks the rest of the batch.

Both `OutboxKafkaTopicConfig` and `OutboxPublisherScheduler` are
`@ConditionalOnProperty(..., havingValue = "true")` on
`ledgerguard.outbox.publisher.enabled`. Every PostgreSQL-only integration
test suite sets this to `false` in the shared `application-test.yml`
specifically so it never creates the `NewTopic` bean or registers the
scheduled poll — meaning it never attempts a Kafka connection at all.
Only `OutboxPublisherIntegrationTest` overrides it back to `true`,
alongside a real Kafka Testcontainer.

### Per-event transaction boundary and row-lock coordination

`OutboxPublisherScheduler` and `OutboxPublisher` are deliberately two
separate Spring beans — `OutboxPublisher.publishIfPending` is
`@Transactional`, and calling it through self-invocation from within the
same bean would silently bypass Spring's transactional AOP proxy, so the
scheduler must call it on the *other* bean for that boundary to be real.
There is no batch-wide transaction: each candidate gets its own
independent PostgreSQL transaction, deliberately, because wrapping an
entire batch in one transaction would mean a later candidate's send
failure rolls back every earlier candidate's already-broker-acknowledged
send back to "pending" too — manufacturing exactly the kind of avoidable
duplicate this design otherwise goes out of its way to minimize.

Per candidate, inside its own transaction (`OutboxEventRepository.lockPendingById`):

```sql
SELECT * FROM outbox_event WHERE id = :id AND published_at IS NULL
FOR UPDATE SKIP LOCKED
```

1. **What concurrent publishers race on:** the same `outbox_event` row —
   whether two threads in one instance, or two separate application
   instances entirely, both attempting to claim the same still-pending
   candidate at nearly the same moment.
2. **Why only the row-lock owner sends the record:** `FOR UPDATE` is a
   real PostgreSQL row lock; only one transaction can hold it on a given
   row at a time. `OutboxPublisher` only calls `kafkaTemplate.send(...)`
   *after* successfully obtaining that lock (i.e., after this query
   returns a non-empty result) — a transaction that doesn't hold the lock
   structurally never reaches the send call at all.
3. **Why `SKIP LOCKED` avoids unnecessary blocking:** without it, a
   second transaction racing for the same row would simply wait
   (block) until the first releases its lock via commit/rollback — wasted
   time for a row that transaction was never going to be allowed to claim
   anyway. `SKIP LOCKED` instead excludes any already-locked row from the
   result set immediately, so the loser's query returns empty right away.
4. **Why a second publisher skips an owned row:** an empty result from
   `lockPendingById` is treated as a plain no-op in `publishIfPending` —
   no exception, no Kafka call, nothing logged as a failure, since
   nothing actually went wrong; another transaction simply owns this
   candidate right now.
5. **Why an already-published row cannot be sent again during normal
   operation:** `lockPendingById`'s own `WHERE ... published_at IS NULL`
   excludes it from the lock attempt in the first place — a committed,
   published row is never even a match.
6. **Why no JVM-local lock is required, and why this works across
   multiple application instances:** the coordination is entirely a
   PostgreSQL row lock, held and released by the database itself for the
   lifetime of one SQL transaction — there is no in-memory map, no
   `synchronized` block, and nothing that assumes a single JVM. Any number
   of application instances connected to the same PostgreSQL database
   race on the exact same row-level lock, exactly as if they were threads
   within one process; the database, not application code, is the single
   source of coordination truth — the same principle every other
   concurrency guarantee in this project (account-row locking, the Task 10
   advisory lock) already relies on.

### Successful publication and where `published_at` is set

`OutboxPublisher.publishIfPending`, once the row is locked: calls
`kafkaTemplate.send(topic, aggregateId.toString(), payload)` and blocks —
via `CompletableFuture.get(sendTimeoutMillis, MILLISECONDS)` — for the
broker's acknowledgement, synchronously, before doing anything else. Only
*after* that call returns successfully does it call
`OutboxEvent.markPublished(Instant.now())` — the one narrow mutator this
task adds to the entity (previously fully immutable after Task 11) — and
`repository.flush()`, surfacing any trigger/constraint problem inside
this same transaction rather than at a later, unrelated flush point. The
method then returns normally, and Spring commits the transaction,
releasing the row lock. `published_at` is therefore never set before a
real, successful acknowledgement, and the V3 `published_at NULL ->
non-null` trigger remains the actual source of truth for what mutation is
permitted — the entity's one mutator does not attempt to duplicate that
check client-side.

### Publication failure and retry

If `send(...).get(...)` throws (a network failure, a broker-side
rejection, or the bounded timeout expiring), `OutboxPublisher` wraps it in
`OutboxPublishException` (unchecked) and lets it propagate — never
caught and suppressed. Spring's default rollback-on-unchecked-exception
behavior rolls back the whole per-event transaction: the row lock
releases, `published_at` stays `NULL` (the `markPublished` call is never
reached), and nothing about the event, the ledger, or the Task 10
idempotency record changes. A concise, safe diagnostic is logged — the
event id, its event type, and the exception's class name — never the full
payload, never a stack trace at high frequency, never anything from the
original HTTP request. The row remains a normal pending candidate for the
next polling cycle (or the next candidate discovery call) to attempt
again, with no special "retry" state beyond `published_at` still being
`NULL` — exactly the minimal retry model `docs/TASKS.md`'s Task 12 entry
describes.

### Why financial requests never depend on Kafka

`DepositService`/`TransferService` are completely unchanged by Task 12 —
they still only ever write the `outbox_event` row (Task 11), inside the
same transaction as the financial write, and return. No deposit or
transfer request thread ever calls `OutboxPublisher` or touches
`KafkaTemplate`. Publication happens later, independently, driven by
`OutboxPublisherScheduler`'s own poll — so Kafka being completely down at
the moment a deposit or transfer is submitted has no effect on that
request's success; the event simply stays pending until the broker (or
network) recovers and a later poll publishes it.

### Why a Task 10 replay produces no duplicate record

Unchanged from Task 11's own reasoning (see "Transactional Outbox"
above): `OutboxEventFactory` — the only code that ever inserts a new
`outbox_event` row — is reachable only from the `IdempotencyService`
branch that performs a genuinely new financial write. A replay or
conflict never reaches it, so there is never a second row for Task 12 to
publish in the first place; `uq_outbox_event_identity` remains the
database-level backstop for the same guarantee.

### At-least-once and the acknowledgement window

Exact semantics, matching `docs/TASKS.md`'s Task 12 entry and
`docs/REQUIREMENTS.md`:

- **Before Kafka acknowledgement:** `published_at` is `NULL`. If the
  process crashes, the send never completed, or the timeout expires, the
  row is untouched and a later poll retries it from scratch.
- **After Kafka acknowledgement but before the PostgreSQL `published_at`
  commit:** Kafka already durably has the record. If the process crashes,
  or the `UPDATE`/commit itself fails, the row is still `published_at IS
  NULL` — a later poll will claim it again and publish it *again*,
  producing a second, distinct Kafka record for the same event.
- **After the PostgreSQL commit:** `lockPendingById`'s `WHERE published_at
  IS NULL` excludes the row from every future candidate selection —
  normal polling never revisits it.

This is why Task 12 provides **at-least-once** publication, never
exactly-once: the second window above is a real, unavoidable gap without
a distributed (two-phase) transaction spanning PostgreSQL and Kafka, which
this task deliberately does not add (see below). The stable `eventId`
already present in every payload (Task 11) is exactly what lets a future
Task 13 consumer detect and discard that duplicate.

**Why Kafka producer idempotence does not close this window.**
`enable.idempotence=true` makes the *producer* de-duplicate its own
retried sends within one broker session (e.g. a retried send after a
transient network blip, before this call returns) — it says nothing about
what happens *after* this call already returned successfully. It is
scoped to one producer instance's in-flight requests, not to surviving a
process crash or restart; a brand new publish attempt after a crash is,
from the idempotent producer's perspective, an entirely new, legitimate
send, not a retry it would suppress.

**Why this is not hidden with Kafka transactions, `REQUIRES_NEW`, or
two-phase commit.** A Kafka transaction can make a *set of Kafka writes*
atomic with each other, but it cannot make a single PostgreSQL commit and
a single Kafka send atomic with *each other* — that would require a
genuine distributed transaction (e.g. XA) spanning both systems, which
this task does not introduce (per the Task 12 contract). `REQUIRES_NEW`
would only create a second, independent PostgreSQL transaction — it does
nothing to link that transaction's fate to the Kafka call's outcome, and
was explicitly rejected for the same reason Task 10/11 rejected it
elsewhere in this project. Marking `published_at` *before* sending, or
deleting the outbox row after sending, would each trade the current
"maybe published twice" risk for a strictly worse "maybe never published
at all" risk — never done here.

### No Kafka dependency beyond publishing

`spring-boot-starter-kafka` (production) and `testcontainers-kafka` (test
only) are the only two dependencies this task adds. No Kafka Streams, no
Spring Cloud Stream, no Avro, no Schema Registry, no Kafka Connect, no
Debezium, no ZooKeeper (the Testcontainers broker and the local Compose
broker are both single-node KRaft), and no external outbox library. No
`@KafkaListener`, no consumer factory, no consumer group business logic,
and no settlement/reconciliation code exist anywhere in this codebase —
Task 13 is the first task that will add consumption.

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

## Error Handling (implemented, Task 7)

`common.GlobalExceptionHandler` (`@RestControllerAdvice`) is now the
single point in the application that translates an exception into an HTTP
response, for every controller. It replaces
`common.AccountAndTransferExceptionHandler` (introduced in Task 5,
extended in Task 6), which returned a bare `{message}` body — that shape
never matched `docs/API_SPEC.md`'s documented `ApiError` envelope
(`timestamp`/`status`/`error`/`message`/`path`); closing that gap is what
Task 7 exists to do. The replacement was verified behavior-preserving for
every status code the old handler already produced: `AccountCreationIntegrationTest`,
`DepositIntegrationTest`, `TransferIntegrationTest`, and
`AccountQueryIntegrationTest` (which between them assert dozens of exact
status codes, but never the old handler's body shape — no test asserted on
`{message}` specifically) all still pass unmodified.

**What's actually thrown, and why each handler exists** (each one verified
against this application's real controller signatures, not assumed from
the Spring Framework's full exception catalog):

- `AccountNotFoundException` → 404. `UnsupportedCurrencyException`/
  `CurrencyMismatchException`/`InsufficientFundsException`/
  `SameAccountTransferException` → 422 (`HttpStatus.UNPROCESSABLE_CONTENT`
  — the non-deprecated RFC 9110 name; its reason phrase is
  `"Unprocessable Content"`, not the older `"Unprocessable Entity"` text).
  Unchanged from Tasks 3–5.
- `MethodArgumentNotValidException` → 400. Thrown for `@Valid
  @RequestBody` failures (blank `ownerName`, non-positive/over-precision
  `amount`, malformed `currency` pattern, missing `sourceAccountId`, etc.)
  across `CreateAccountRequest`/`DepositRequest`/`TransferRequest`. Field
  errors are sorted by field name before being joined into `message`, so
  a request with multiple problems always produces the same message text
  regardless of Spring's internal validation-traversal order.
- `jakarta.validation.ConstraintViolationException` → 400. The
  transaction-history endpoint's `@Min`/`@Max` on `page`/`size` are
  `@Validated`-driven method-parameter constraints, enforced via Bean
  Validation's AOP method interceptor, which throws this JSR-380 exception
  type directly — distinct from the two exception types above. Violations
  are likewise sorted (by property path) before joining into `message`.
- `HttpMessageNotReadableException` → 400. Covers malformed JSON syntax,
  an unrecognized (protected or unknown) JSON property (Jackson's
  `fail-on-unknown-properties`), and a value that fails to coerce into its
  declared type (a non-numeric `amount`, a malformed UUID embedded in a
  request body). The message is a fixed, generic string
  (`"Malformed request body."`) — the underlying cause is never inspected
  or echoed, since it can contain Jackson/Hibernate class names.
- `MethodArgumentTypeMismatchException` → 400. A path variable that fails
  to convert to its declared type — a non-UUID `{id}`. The message
  includes the parameter name (`"id"`, public API surface) but never the
  rejected value itself.
- `Exception` (catch-all) → 500. Anything not explicitly handled above,
  including a persistence-layer failure not caught by earlier
  application-level validation (e.g. a `DataIntegrityViolationException`
  from a real database constraint). No dedicated handler exists for
  persistence exceptions specifically — `docs/API_SPEC.md` defines no
  distinct status for a database-level conflict, and none of Tasks 3–6's
  write paths can currently produce one through normal API use (the one
  scenario that can, a `NUMERIC(19,4)` overflow, requires directly
  corrupting a balance via raw SQL first — see the deposit/transfer/Task 7
  rollback tests), so routing it to the same generic fallback as any other
  unexpected failure is the only interpretation the approved contract
  actually supports. The original exception is logged once, server-side,
  at `ERROR` level (via SLF4J/Logback, the framework's existing logging
  stack — no new logging dependency), with the request method and path for
  context; the client only ever receives the generic `message`
  `"An unexpected error occurred."`.

**Types deliberately not handled**, because this application's controllers
never actually throw them: `HandlerMethodValidationException` (Spring
6.1's newer web-layer method-validation exception — our
`@Validated`+`@RequestParam` combination goes through the AOP
`ConstraintViolationException` path instead, confirmed empirically in
Task 6), `MissingServletRequestParameterException` (`page`/`size` both
have `defaultValue`s, so they can never be reported "missing"),
`MissingPathVariableException` (no route declares a path variable it
doesn't also bind), and `BindException` (its only production use in this
app, `MethodArgumentNotValidException`, is a subtype already handled
explicitly and takes precedence). Adding handlers for types that can never
fire would be untested, misleading surface area, not genuine coverage.

**Why translating at the HTTP boundary doesn't interfere with
transactional rollback:** `GlobalExceptionHandler` methods run only after
Spring's `DispatcherServlet` has already caught the exception propagating
out of the controller — which itself only happens after the exception has
already propagated out of the `@Transactional` service method
(`DepositService.deposit`, `TransferService.transfer`), which is what
triggers Spring's transactional rollback in the first place. Nothing in
any service `catch`es an exception to convert it to a response — every
domain and persistence exception is left to propagate naturally. By the
time `GlobalExceptionHandler` runs, the database transaction has already
been rolled back or never opened for the failed part of the request; the
handler only builds a response describing what already happened, and
never opens a new transaction or writes anything itself.

## API Documentation (implemented, Task 8)

`springdoc-openapi-starter-webmvc-ui:3.0.2` (the release vetted for Spring
Boot 4.0.7 by start.spring.io) introspects the existing controllers,
request/response DTOs, and Jakarta Validation annotations at runtime to
generate the OpenAPI document — there is no hand-maintained YAML/JSON spec
file to keep in sync, and no generated server stubs or client code. This
is purely descriptive: it adds zero new endpoints, zero new business
behavior, and reads no database state to build the document (the document
describes the API's *shape*, which is fixed at compile time via
reflection over annotations — it never queries `account`,
`ledger_transaction`, or `ledger_entry`).

- `common.OpenApiConfig` supplies the `Info` block (title, description,
  version) — see `docs/API_SPEC.md`'s "OpenAPI/Swagger" section for the
  exact values and why license/contact/server metadata were deliberately
  omitted rather than filled with placeholders.
- `@Operation`/`@ApiResponses`/`@Parameter` on `AccountController` and
  `TransferController` document each endpoint's summary, description,
  path/query parameters, and the exact status codes that endpoint's
  `GlobalExceptionHandler`-routed failures actually produce (Task 7) —
  these annotations describe existing behavior, they don't define new
  behavior.
- `@Schema` on the DTOs mostly just adds `example` values and
  human-readable `description`s — Jakarta Validation annotations already
  present (`@NotBlank`, `@NotNull`, `@Positive`, `@Digits`,
  `@Pattern`, `@Size`) are enough for springdoc to infer accurate
  `required`/`pattern`/`minLength` constraints without any OpenAPI-specific
  annotation duplicating them. The one deliberate override is
  `@Schema(type = "string")` on every `BigDecimal` field (`amount`,
  `balance`, `newBalance`) — springdoc's default inference maps
  `BigDecimal` to a bare JSON `number`, but every monetary value in this
  API is documented and tested as a JSON *string* (e.g.
  `"amount": "100.00"`, per every example throughout `docs/API_SPEC.md`);
  the override makes the generated schema match the actual wire format
  exactly, and doubles as an explicit signal that these are exact decimal
  values, never floating-point.
- The transaction-history endpoint's response schema
  (`PagedResponseTransactionHistoryItem` — springdoc's generated name for
  `PagedResponse<TransactionHistoryItem>`) must be left for springdoc to
  infer directly from the controller method's actual return type.
  Overriding it with an explicit `@Schema(implementation = PagedResponse.class)`
  on the `@ApiResponse` — tried and reverted during Task 8 — erases the
  generic type parameter and produces an empty `content` item schema; the
  code has a comment at that exact spot explaining why it must stay
  untouched. This is also what keeps the documented pagination envelope as
  the project's own `{content, page, size, totalElements, totalPages}`
  shape rather than Spring Data's `Page`/`PageImpl` JSON.
- `springdoc.default-produces-media-type: application/json`
  (`application.yml`) makes every response's documented content type
  `application/json` instead of springdoc's own default wildcard `*/*` —
  the one global configuration property Task 8 added, chosen over
  scattering an explicit `mediaType` attribute across every
  `@Content` annotation (which, worse, also turned out to suppress the
  generic-type inference above whenever combined with an omitted
  `schema`).
- No security scheme is declared anywhere — Phase 1 has no authentication,
  so documenting one would misrepresent these endpoints as protected.

**Endpoints:** `GET /v3/api-docs` (the OpenAPI 3.1 JSON document) and
`GET /swagger-ui/index.html` (interactive UI; `GET /swagger-ui.html`
redirects there) — both springdoc defaults, both already the paths
`docs/API_SPEC.md` referenced before Task 8 existed to implement them.

## Continuous Integration (implemented, Task 9)

`.github/workflows/ci.yml` is the only workflow in the repository. It runs
one job (`ubuntu-latest`) on every push and pull request targeting
`master`, and that job runs exactly one command:
`./mvnw --batch-mode --no-transfer-progress verify` — the same `verify`
lifecycle (compile → unit tests → PostgreSQL Testcontainers integration
tests → every Maven plugin already bound to it) a developer runs locally,
with no test-skipping flag and no CI-only profile that could make the
workflow pass something a local run would fail. `--batch-mode` and
`--no-transfer-progress` only suppress interactive/progress output
appropriate for a non-interactive runner; neither changes what runs or
how failures are reported.

Docker is preinstalled and already running on GitHub-hosted `ubuntu-latest`
runners, so Testcontainers reaches it the same way it does on a
developer's machine (the default local Docker socket) — no
`docker-compose.yml` service container is defined or needed in the
workflow, and none of the eight Testcontainers-backed test classes needed
any change to run in CI. Each one still starts its own fresh
`postgres:16.4` container and runs the Flyway migration from an empty
schema, exactly as documented throughout this file and
`docs/TEST_STRATEGY.md`.

The workflow requests `permissions: contents: read` only — it has no
ability to write to the repository, and contains no deployment,
publishing, or release step of any kind. No secret, credential, or
environment-specific value is referenced anywhere in it. `concurrency`
cancels a stale in-progress run for the same branch/PR (never touching
unrelated branches); Maven's dependency cache (not build output) is keyed
on `pom.xml` via `setup-java`'s `cache: maven`; Surefire/Failsafe reports
upload as a build artifact only when the job fails, with a 7-day
retention, and never block an otherwise-successful run if no report
happens to exist.

Task 9 changed no application code, no database object, and no test
assertion — it only adds a workflow file that runs the pre-existing,
already-passing verification suite automatically.
