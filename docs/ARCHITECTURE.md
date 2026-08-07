# Architecture

> **Status: Phase 1 complete (Tasks 1–9). Phase 2 complete (Tasks 10–16):
> idempotency, transactional outbox, Kafka publishing, Kafka consumption,
> settlement CSV import, settlement reconciliation, and reliability
> hardening.** Database
> layer (Task 2), account creation (Task 3), deposits (Task 4), transfers
> (Task 5), account balance/transaction-history reads (Task 6), global
> error handling (Task 7), OpenAPI documentation (Task 8), CI (Task 9),
> idempotency for deposits/transfers (Task 10), the transactional outbox
> (Task 11), publishing pending outbox events to Kafka (Task 12), durably
> deduplicated Kafka consumption (Task 13), settlement CSV import
> (Task 14), settlement reconciliation (Task 15), and reliability/
> concurrency hardening (Task 16 — test-only; no production code changed)
> are all implemented;
> only plain account lookup by id remains unimplemented — no task has
> ever been assigned it. The `account`
> package has account creation,
> deposit processing, and read-only account queries; the `ledger` package
> has `LedgerTransaction`, `LedgerEntry`, and their repositories/enums;
> the `transfer` package has transfer processing; the `idempotency`
> package (Task 10) has the idempotency key record, repository, command,
> service, and conflict exception — see "Idempotency" below; the `outbox`
> package (Tasks 11–12) has the outbox event record, repository,
> event-type enums, the two version-1 event payload records, the event
> factory, the publisher scheduler, the per-event transactional
> publisher, the Kafka topic configuration, and validated publisher
> properties — see "Transactional Outbox" and "Kafka Publishing" below;
> the `inbox` package (Task 13) has the Kafka listener, the
> transactional event processor, the JDBC-based processed-event
> repository, strict event validation, and validated consumer properties
> — see "Kafka Consumption" below; the `settlement` package (Task 14)
> has the multipart controller, the CSV parser, the transactional import
> processor, the two JDBC-based repositories, validated import
> properties, and the hashing/normalization utilities — see "Settlement
> Import" below; the new `reconciliation` package (Task 15) has the
> command/read controller, the orchestrating service, the transactional
> matching processor, the pure matcher, the bulk ledger-data loader, and
> four JDBC-based read/write repositories — see "Settlement
> Reconciliation" below. The `common` package holds
> `PagedResponse<T>` (Task 6), `ApiError` and `GlobalExceptionHandler`
> (Task 7), and `OpenApiConfig` (Task 8 — see "API Documentation" below).
> Task 9 added `.github/workflows/ci.yml` only — no application code, no
> new package, no behavior change (see "Continuous Integration" at the
> end of this document). `GET /api/v1/accounts/{id}` still does not
> exist, so the public-vs-internal lookup split described below is still
> only partially realized (see that section for exactly what deposits,
> transfers, and the read endpoints do instead). Authentication remains
> unimplemented — see `docs/TASKS.md` for what Task 16+ still covers;
> Task 12 provides **at-least-once** publication
> and Task 13 makes the PostgreSQL-side consumer effect idempotent for a
> given `eventId` — neither claims, nor together produce, exactly-once
> Kafka delivery. Task 13 performs no settlement, reconciliation, balance
> update, or ledger mutation of any kind. Task 14 only records immutable
> external settlement observations — it performs no reconciliation, and
> no ledger, balance, outbox, Kafka, processed-event, or idempotency
> mutation of any kind. Task 15 compares those observations against the
> ledger and durably records the classification — it likewise performs no
> ledger, balance, account, settlement-evidence, outbox, Kafka,
> processed-event, or idempotency mutation of any kind, and no automated
> correction.

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

- `inbox` — **implemented (Task 13)**: `LedgerEventConsumer` (the
  `@KafkaListener`), `LedgerEventProcessor` (the per-event
  `@Transactional` validate-and-claim logic), `ProcessedEventRepository`
  (`NamedParameterJdbcTemplate`-based), `LedgerEventValidator`,
  `LedgerConsumerProperties` (validated configuration),
  `LedgerEventConsumerConfig` (the dedicated consumer/listener-container
  factory pair), `ValidatedLedgerEvent`, `ProcessedEventRecord`,
  `PayloadHasher`, `LedgerEventValidationException`, and
  `ConflictingEventException` — see "Kafka Consumption" below.

Packages named in `CLAUDE.md` for later phases (`settlement`,
`reconciliation`, `security`, `audit`) are **not yet created** —
`idempotency` (Task 10), `outbox` (Task 11), and `inbox` (Task 13,
mirroring `outbox`'s naming for the consumer-side counterpart of the same
pattern) are the three Phase 2/3 packages added so far.

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
broker are both single-node KRaft), and no external outbox library. Task 12
itself adds no `@KafkaListener` or consumer — see "Kafka Consumption"
below for that.

## Kafka Consumption (implemented, Task 13)

Consumes Task 12's published records from `ledger.transaction-events.v1`
and durably records successful processing in PostgreSQL, keyed by the
event's own stable `eventId` — the consumer-side half of the outbox/inbox
pattern. This section covers persistence and deduplication only: nothing
in this codebase reacts to a processed event (no settlement, no
reconciliation, no balance/ledger mutation, no notification) — that is
explicitly future work.

### Consumer group and record contract

Consumer group `ledgerguard-transaction-event-consumer-v1` (configurable
via `ledgerguard.inbox.consumer.group-id`), subscribed to the same
configurable topic Task 12 publishes to
(`ledgerguard.inbox.consumer.topic`, default
`ledger.transaction-events.v1` — the two properties are independently
configurable but share the same default, so a default deployment always
points at the same topic on both sides). Records are consumed as plain
UTF-8 strings (`StringDeserializer` for both key and value, mirroring the
producer's `StringSerializer`) — the key is expected to be the ledger
transaction UUID, the value the exact Task 11/12 JSON payload. No new
Kafka headers are required or read; the key and JSON value are sufficient.

### Strict validation

`LedgerEventValidator` parses the raw value with the application's real
Jackson 3 `ObjectMapper` into a tree (never binding directly into a
lenient DTO) and validates by hand, deliberately not via global Jackson
configuration or record-based strict binding — neither can express rules
like "amount must be a positive, exactly-four-decimal string" or "source
and destination must differ." Only `DEPOSIT_COMPLETED` and
`TRANSFER_COMPLETED` at `schemaVersion` exactly `1` (an integer, not a
string or a floating-point literal) are accepted; every other event type
or schema version is rejected outright — never silently treated as
processed. Validated in full: the JSON is a single object (not an array,
scalar, or `null`); the field set for the declared event type is *exactly*
right (no missing field, no unexpected one — including a transfer-only
field like `sourceAccountId` appearing on a deposit); `eventId`,
`transactionId`, and (for transfers) `sourceAccountId`/
`destinationAccountId` are syntactically valid UUIDs; `occurredAt` parses
as an ISO-8601 `Instant`; `amount` is a JSON *string* (never a bare
number) matching `^\d+\.\d{4}$` and is positive; `currency` is exactly
uppercase `"USD"`; the Kafka record key equals `transactionId` in
standard UUID string form; and, for transfers, `sourceAccountId` differs
from `destinationAccountId`. Any violation throws
`LedgerEventValidationException` with a safe, generic message — never the
offending payload content. A successful validation yields a
`ValidatedLedgerEvent` (`eventId`, `aggregateId`, `eventType`,
`schemaVersion` only — Task 13 does not persist the full payload; see
"Payload fingerprint, not payload storage" below).

### Payload fingerprint, not payload storage

`PayloadHasher.sha256Hex` computes SHA-256 over the *exact* UTF-8 bytes of
the raw Kafka value string — never by deserializing and reserializing it,
so the fingerprint can't drift from what was actually on the wire due to
key reordering or whitespace differences a round-trip might introduce.
This hash, not the payload itself, is what `processed_event` stores
(`payload_hash`, a lowercase 64-character hex `CHAR(64)`) — enough to
distinguish an identical redelivery from a conflicting reuse of the same
`eventId` without duplicating the entire financial payload into a second
table.

### Duplicate claim algorithm

`LedgerEventProcessor.process(...)` (a separate Spring bean from
`LedgerEventConsumer`, specifically so its `@Transactional` proxy is
effective — self-invocation from within the listener would silently
bypass it, the same reason `outbox.OutboxPublisher` is separate from
`outbox.OutboxPublisherScheduler`) does everything for one record inside
one PostgreSQL transaction:

1. Validate (above); a validation failure throws before anything is
   attempted against the database.
2. Compute `payload_hash`.
3. Call `ProcessedEventRepository.tryClaim`, which executes:
   ```sql
   INSERT INTO processed_event
       (event_id, aggregate_id, event_type, schema_version, payload_hash,
        source_topic, source_partition, source_offset)
   VALUES (...)
   ON CONFLICT (event_id) DO NOTHING
   ```
   and reports success by the JDBC affected-row count (`1` = inserted,
   `0` = a row for this `event_id` already existed) — **not** by catching
   a constraint-violation exception. This is deliberate: a normal JPA
   `save()` hitting the primary key would throw a `PersistenceException`
   that marks the whole `EntityManager`/transaction rollback-only, making
   "this event was already claimed" indistinguishable from "this
   transaction is now unusable" — exactly the exception-driven duplicate
   control flow the Task 13 contract forbids. `ProcessedEventRepository`
   is therefore deliberately implemented with
   `NamedParameterJdbcTemplate`, not JPA, unlike every other repository in
   this project.
4. **1 row inserted (first delivery):** this invocation owns first
   processing of that `eventId`. The insert itself is the entire
   consumer-side effect; the method returns normally.
5. **0 rows inserted (a row already exists):** load the existing row
   (`findByEventId`) and compare `aggregate_id`, `event_type`,
   `schema_version`, and `payload_hash` (`ProcessedEventRecord.matches`)
   against this delivery — **never** comparing `source_topic`/
   `source_partition`/`source_offset`, since a legitimate redelivery of
   the same event may land at a different Kafka position entirely.
   - **All four match (identical duplicate):** a genuine success, not a
     mutation — the method returns normally without touching the
     database again.
   - **Any differ (conflicting duplicate):** throws
     `ConflictingEventException`. The existing row is never touched; no
     second row is inserted (`event_id` is the primary key).

A source-position (`source_topic`/`source_partition`/`source_offset`)
uniqueness violation — a *different* `event_id` claiming a Kafka position
another event already occupies, which never happens under correct
redelivery — is not specially handled; it propagates as a genuine
failure. This is a corruption safeguard, not the duplicate-detection
mechanism: `event_id` alone is the logical duplicate identity (see
`docs/DATA_MODEL.md`'s "Processed Event Table" section for why Kafka
coordinates are deliberately not used as the primary key).

### Why this is safe under concurrent consumers, multiple partitions, and multiple application instances

The `INSERT ... ON CONFLICT (event_id)` claim is resolved entirely by
PostgreSQL, which allows exactly one transaction to successfully insert a
given primary key — regardless of whether the competing attempts come
from two listener threads in one process, two consumers on different
partitions, or two entirely separate application instances sharing the
same database. There is no JVM-local map, `synchronized` block, or
single-instance assumption anywhere in this path — the database is the
only source of coordination truth, the same principle every other
concurrency guarantee in this project already relies on (account-row
locking, the Task 10 advisory lock, the Task 12 `FOR UPDATE SKIP LOCKED`
claim).

### Kafka acknowledgement boundary

The listener container (`inbox.LedgerEventConsumerConfig`) is configured
with `enable.auto.commit=false` and
`ContainerProperties.AckMode.MANUAL_IMMEDIATE`. `LedgerEventConsumer`
calls `Acknowledgment.acknowledge()` **only** after
`LedgerEventProcessor.process(...)` returns normally — and that method is
`@Transactional`, so it cannot return normally until its PostgreSQL
transaction has committed. The required ordering (validate → PostgreSQL
commit → Kafka acknowledgement) therefore falls out of this structure by
construction, not from a manually sequenced try/finally that could drift
out of sync.

On any failure — a validation error, a `ConflictingEventException`, or a
transient failure such as a database outage — the listener calls
`Acknowledgment.nack(Duration)` instead of silently continuing to the
next record. This is a deliberate choice, not the default "just don't
ack": with manual-immediate acknowledgement, Kafka's committed offset is
a single per-partition cursor, not a per-record ledger — if a *later*
record on the same partition were acknowledged while an earlier one was
merely left un-acked, the commit would silently advance past the
earlier failure the next time any record in that partition committed,
permanently skipping it. `nack(Duration)` avoids this: it re-seeks the
consumer back to the failed record (and everything already fetched after
it on that partition) so it is redelivered after a bounded backoff,
rather than ever being silently bypassed. Every failure category uses the
same fixed, bounded backoff and the same non-skipping behavior — a
permanently invalid or conflicting record therefore keeps its partition
positioned at that record until it is corrected or removed upstream. This
is a deliberate, documented limitation for Task 13: dead-letter handling
for a genuinely poison record is out of scope here (see `docs/TASKS.md`'s
Task 16 entry).

### The duplicate/redelivery window, precisely

- **Before the PostgreSQL commit:** no `processed_event` row exists for
  this `eventId`. A failure at any point (validation, a database outage
  mid-transaction, a crash) leaves nothing behind; the record is
  redelivered (via `nack`, or via a fresh poll after a consumer restart)
  and the next attempt starts fresh.
- **After the PostgreSQL commit but before the Kafka offset commit:** the
  `processed_event` row exists. If the process crashes in this narrow
  window, Kafka will redeliver the record (since its offset was never
  committed) — the redelivery finds the existing `event_id`, the
  fingerprint comparison finds an identical match, and the redelivery
  becomes a no-op success whose offset can then be acknowledged/committed
  normally.
- **After the Kafka offset commit:** normal consumption proceeds past
  this record; it is never redelivered under ordinary operation.

Kafka delivery therefore remains **at-least-once** — this project never
claims otherwise. What Task 13 actually guarantees is that the
**PostgreSQL-side effect** of processing a given `eventId` is idempotent:
however many times the same event is redelivered, `processed_event` ends
up with exactly one row for it, and every redelivery after the first is
a safe no-op. This is not global exactly-once processing across arbitrary
external systems — only the specific PostgreSQL side effect Task 13
itself introduces.

**Why this isn't hidden behind Kafka transactions, `REQUIRES_NEW`, or
two-phase commit** — the same reasoning Task 12 already applied to
publishing: none of these can make a single Kafka offset commit and a
single PostgreSQL commit atomic with each other without a genuine
distributed transaction spanning both systems, which Task 13 does not
introduce. Marking a record as processed before actually processing it,
or acknowledging before the PostgreSQL commit, would trade today's
"maybe processed twice" risk (already handled safely by the `eventId`
claim) for a strictly worse "maybe never durably processed at all" risk —
never done here.

### No downstream business mutation

`LedgerEventProcessor.process(...)` never calls `DepositService` or
`TransferService`, never constructs a `LedgerTransaction`/`LedgerEntry`,
never updates an `Account` balance, never touches `outbox_event`
(including `published_at`), and never touches `idempotency_key`. The
`processed_event` insert described above is Task 13's only effect,
by construction — there is no code path in `inbox` that reaches any of
those other tables at all.

## Settlement Import (implemented, Task 14)

Imports a CSV file of settlement observations reported by an external
bank or payment processor, via `POST /api/v1/settlement-imports`
(`multipart/form-data`, `source` text field + `file` CSV part). Task 14
**only records immutable external observations** — it does not reconcile
them against LedgerGuard's own ledger (that is Task 15's job) and never
mutates any account, ledger, outbox, processed-event, or idempotency
state. The `settlement` package: `SettlementImportController` (multipart
request validation only, no CSV/SQL logic), `SettlementImportService`
(bounded file reading, exact SHA-256 file hashing, orchestration —
no database transaction of its own), `SettlementCsvParser` (strict
RFC 4180 parsing via Apache Commons CSV, no database access),
`SettlementImportProcessor` (a separate `@Transactional` bean — the same
self-invocation-avoidance reason as `OutboxPublisher`/`LedgerEventProcessor`
— that performs the whole-file atomic import), `SettlementImportRepository`/
`SettlementRecordRepository` (`NamedParameterJdbcTemplate`-based atomic
`INSERT ... ON CONFLICT DO NOTHING`, deliberately not JPA, the same reason
as `ProcessedEventRepository`), `SettlementImportProperties` (validated
`ledgerguard.settlement.import.*` config), `SettlementCsvRow`/
`StoredSettlementImport`/`StoredSettlementRecord`/`SettlementImportOutcome`,
`RowFingerprint`/`Sha256`/`SourceNormalizer` (small, pure hashing/encoding
utilities), and the exception types
`SettlementImportDisabledException`/`InvalidSettlementRequestException`/
`UnsupportedSettlementContentTypeException`/`SettlementFileTooLargeException`/
`SettlementRowLimitExceededException`/`SettlementConflictException`, all
mapped to their documented status codes by `GlobalExceptionHandler`.

### CSV contract

Exact header order required: `external_reference,transaction_id,amount,
currency,settled_at` — any missing/extra/reordered/duplicate/unknown
column rejects the whole file (400). UTF-8 only (an optional leading BOM
is stripped before hashing comparisons occur at the parsing layer, but
`file_hash` below is always computed over the *original*, pre-strip
bytes); CRLF and LF line endings; standard CSV quoting, escaped quotes,
embedded commas, and embedded newlines inside quoted fields, via Apache
Commons CSV — never hand-rolled comma-splitting. `amount` must be a plain
decimal string with exactly two decimal places, greater than zero, no
scientific notation, no locale-specific grouping — parsed with
`BigDecimal`, never `float`/`double`. `currency` must be exactly three
uppercase ASCII letters *and* a currency LedgerGuard actually supports
(USD only in Phase 1, mirroring `AccountService`'s list) — but Task 14
never compares the reported amount/currency against the referenced
transaction's actual values; that comparison, if any, belongs to Task 15.
`transaction_id` must be a canonical UUID string but is accepted whether
or not it matches an existing `ledger_transaction` row — an unmatched
reference is retained as evidence for future reconciliation, not
rejected. `settled_at` must be an ISO-8601 instant with an explicit UTC
offset (a bare local timestamp without an offset is rejected).
`external_reference` must be non-blank, printable text with no control
characters, up to `ledgerguard.settlement.import.max-external-reference-length`.
A repeated `external_reference` within one file unconditionally rejects
the whole file (400) — the simpler, more unambiguous of the two contracts
the Task 14 specification allowed, rather than tolerating an identical
intra-file repeat while rejecting only a conflicting one.

### File identity, row identity, and hashing

`file_hash` is the lowercase SHA-256 hex digest of the *exact* uploaded
bytes, computed before any BOM removal, UTF-8 decoding, or CSV parsing —
two byte-distinct files with logically equivalent rows hash differently.
The logical file identity is `(normalized_source, file_hash)`.
`normalized_source` is the lowercase form of the trimmed `source` field
(`SourceNormalizer`) — the display value is preserved separately in
`settlement_import.source`.

A settlement observation's logical identity is
`(normalized_source, external_reference)`. Its fingerprint (`row_hash`)
is a SHA-256 hex digest over a **length-prefixed** canonical encoding
(`RowFingerprint`) of every business field — `<byte-length>:<value>` for
each of normalized source, external reference, canonical transaction
UUID, two-decimal amount, uppercase currency, and the settled-at instant
normalized to ISO-8601 UTC — concatenated in a fixed order. Length
prefixing (not a delimiter-joined string) makes every field boundary
unambiguous regardless of its content, so two different field tuples can
never collide onto the same canonical string.

### Duplicate and conflict behavior

| Scenario | Behavior |
|---|---|
| Same `(normalized_source, file_hash)` re-uploaded | 200, the original committed import result, `replayed: true`. No new `settlement_import` row, no new `settlement_record` rows. |
| A byte-distinct file containing a row identical (same `row_hash`) to an already-stored observation | 201 for the new import; that row is counted in `duplicateRows`, not re-inserted. |
| A row whose `(normalized_source, external_reference)` already exists with a *different* `row_hash`/business fields | 409, the **entire file's import is rolled back** — no `settlement_import` row, no `settlement_record` rows from that file, and the original stored observation is left untouched. |

Claims are atomic PostgreSQL operations — `INSERT ... ON CONFLICT DO
NOTHING`, then (only if that insert claimed nothing) a plain `SELECT` to
compare the existing row's hash — never an unlocked "SELECT, then insert
if absent," never a JVM lock, static map, or cache, and never a JPA
constraint-violation exception used as duplicate control flow (the same
concurrency-safety pattern `outbox`/`inbox` already established).
`SettlementImportProcessor.importFile(...)` claims every row first,
computing final `insertedRowCount`/`duplicateRowCount`, and inserts the
`settlement_import` row **last**, since that table is append-only and
must never be inserted with placeholder counts and updated afterward;
`settlement_record.first_import_id`'s foreign key is declared
`DEFERRABLE INITIALLY DEFERRED` in `V5` specifically so a row can
reference that not-yet-inserted import id without failing the constraint
mid-transaction. Under a genuine concurrent race for the same
`(normalized_source, file_hash)`, PostgreSQL blocks a conflicting insert
against an uncommitted row until the first transaction resolves; the
loser's row claims therefore already resolve as identical duplicates
against the winner's now-committed data, and the loser's own final
`settlement_import` claim then also loses, so it returns the winner's
result as a replay — correct across multiple application instances with
zero JVM-local coordination.

### Configuration and limits

`ledgerguard.settlement.import.{enabled, max-file-size-bytes,
max-row-count, max-source-length, max-external-reference-length}`
(`SettlementImportProperties`, validated, defaults: `true`, 5 MiB,
10 000, 64, 128). `spring.servlet.multipart.max-file-size`/
`max-request-size` (`application.yml`) is an *outer* boundary only — the
actual enforced limit is the smaller, independently configurable Task 14
property, checked again inside `SettlementImportService` regardless of
the framework-level setting. When disabled, every import request
receives one explicit 503 response; no other endpoint is affected.

### Filename and content handling

The submitted filename is never trusted for identity, parsing, or
authorization, and is never used to construct a filesystem path — only a
sanitized basename (directory components stripped, length-capped) is
stored as `settlement_import.original_filename`, audit metadata only. No
uploaded file is ever written to an application-controlled path; the only
filesystem interaction is reading `MultipartFile#getInputStream()`, a
framework-managed temporary resource. Raw CSV content is never logged,
and no field value is ever reflected into an error message, log line, or
API response — every validation error carries only a row number and a
hardcoded field name.

## Settlement Reconciliation (implemented, Task 15)

Compares immutable Task 14 settlement observations against LedgerGuard's
own immutable ledger and durably records the classification. **Task 15
performs no reconciliation-driven mutation of any kind** — it never
creates or modifies ledger transactions/entries, never changes an account
or its balance, never modifies `settlement_import`/`settlement_record`,
never touches `idempotency_key`/`outbox_event`/`processed_event`, never
calls `DepositService`/`TransferService`, and never auto-corrects a
discrepancy. Its only effect is inserting `reconciliation_run`/
`reconciliation_result` rows.

Three endpoints, all under `/api/v1/settlement-imports/{importId}/reconciliation`:
`POST` (the command), `GET` (the summary), `GET .../results` (paginated
item-level results). No update, delete, retry, correction, export,
administration, or reconciliation-by-source endpoint exists.

New `reconciliation` package: `ReconciliationController` (path/pagination
validation only), `ReconciliationService` (orchestration and the two
"import exists"/"run exists" 404 checks), `ReconciliationProcessor` (a
separate `@Transactional` bean — the same self-invocation-avoidance reason
as `SettlementImportProcessor`/`LedgerEventProcessor` — that computes and
atomically commits one run), `ReconciliationMatcher` (the pure, DB-free
matching algorithm), `LedgerDataLoader` (bulk-loads ledger data),
`ReconciliationRunRepository`/`ReconciliationResultRepository`
(`NamedParameterJdbcTemplate`-based, the same reason as every other
append-only claim table in this project), and two small, deliberately
independent read-only repositories,
`SettlementImportSummaryRepository`/`SettlementObservationRepository`,
that query `settlement_import`/`settlement_record` directly rather than
depending on the `settlement` package's own (deliberately package-private)
repository types — Task 15 must not depend on or modify Task 14's
internals, and this keeps that boundary absolute.

### Reconciliation scope: first_import_id, not "every row in the file"

A reconciliation run reconciles the settlement observations **first
recorded by** one settlement import —
`settlement_record.first_import_id = settlement_import.id` — not
necessarily every logical row that import's uploaded file contained. A
row a file duplicated from an earlier import belongs to its *original*
observation and *original* import (Task 14 deliberately stores no
import-to-observation many-to-many mapping — see `docs/DATA_MODEL.md`'s
"Settlement Import Tables" section); Task 15 does not add one either. An
import containing only previously-known duplicate rows legitimately
produces a **zero-result run** — `total_result_count = 0` is valid, not
an error. The response summary makes this unambiguous with four distinct
counts: `importedFileRows` (`settlement_import.total_row_count`),
`newlyRecordedObservations` (`settlement_import.inserted_row_count`),
`duplicateRows` (`settlement_import.duplicate_row_count`), and
`reconciliationResultCount` (this run's own result count — equal to
`newlyRecordedObservations` by construction, since every row a given
import first recorded gets exactly one result). `importedFileRows` can
exceed `reconciliationResultCount` whenever the file also duplicated rows
from an earlier import.

### Matching algorithm and eligibility

Only `DEPOSIT` is settlement-eligible — deposits are the only transaction
type that crosses the system boundary (`DEBIT EXTERNAL_FUNDING`);
transfers move value only between two internal customer accounts, so
there is no reason an external source would ever report one. A reported
`TRANSFER` is classified `INELIGIBLE_TRANSACTION_TYPE`, never compared
against any amount/currency.

For an eligible deposit, the **complete posting structure is revalidated
independently** before its amount/currency are trusted — exactly two
entries; one `DEBIT` against a `SYSTEM`/`ASSET`/`EXTERNAL_FUNDING`
account; one `CREDIT` against a `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`
account; both positive; equal amounts (via `BigDecimal.compareTo`, never
`equals` — reported amounts are `NUMERIC(19,2)`, internal amounts are
`NUMERIC(19,4)`, different scales for numerically-equal values; never a
`float`/`double`); equal currencies. Any violation — wrong entry count,
wrong account taxonomy on either leg, reversed debit/credit direction,
unequal amount or currency between the two legs, a non-positive amount —
classifies `INTERNAL_LEDGER_INCONSISTENT`, a finding about LedgerGuard's
own data, never about the external report. `ReconciliationMatcher` is a
pure function (see its Javadoc for the exact classification precedence)
with no database access, fully unit-tested in isolation.

`account.balance` is never read or compared anywhere in this algorithm —
the authoritative amount/currency come only from the two validated
`ledger_entry` legs, the same "ledger over materialized balance" principle
established in "Ledger as Source of Truth vs. Materialized Balance"
above.

### Classifications

`MATCHED`, `INTERNAL_TRANSACTION_NOT_FOUND`, `INELIGIBLE_TRANSACTION_TYPE`,
`AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `AMOUNT_AND_CURRENCY_MISMATCH`,
`INTERNAL_LEDGER_INCONSISTENT` — mutually exclusive by construction (the
matcher stops at the first applicable condition), so one enum column is
sufficient. Every value except `INTERNAL_LEDGER_INCONSISTENT` (a
data-integrity finding) is an ordinary, expected reconciliation outcome —
including an unmatched or ineligible reference — returned as result data
in a successful 2xx response, **never** as an HTTP-level command failure.
`settled_at` is never compared to anything (no internal timestamp shares
its meaning — `ledger_transaction.created_at` measures when LedgerGuard
itself posted the transaction, not when an external processor settled
it); there is no `TIMESTAMP_MISMATCH` outcome. Missing-external detection
(concluding an internal deposit *should* have appeared in an external
file but didn't) is out of scope — `settlement_import`/`settlement_record`
record nothing about expected provider coverage, settlement period, file
completeness, or allowed delay.

### Persistence, identity, and replay

Two new append-only tables (`V6__add_settlement_reconciliation.sql`;
`V1`–`V5` unchanged) — see `docs/DATA_MODEL.md`'s "Settlement
Reconciliation Tables" section for the full schema. A run's logical
identity is **`(settlement_import_id, algorithm_version)`, not
`settlement_import_id` alone** — Task 15 always writes
`algorithm_version = 1` (there is no public API to select a version), so
a repeated command for the same import replays the existing committed
run (200) rather than computing a second "true" answer for the same
question; a future, separately-approved algorithm version could produce
a genuinely new immutable run against the same import without being
rejected as a duplicate. Every reconciliation-result row snapshots its
reported values (from `settlement_record`) and its validated internal
values (from `ledger_entry`) at creation time rather than only
referencing them by id — both sources are themselves immutable, so
snapshotting is safe and makes a historical result independently
interpretable without a join.

Every claim is atomic PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`,
never an unlocked "SELECT, then insert if absent," never a JVM lock,
static map, or cache — the same concurrency-safety pattern every other
append-only claim table in this project already establishes. The
transactional workflow, all inside one `ReconciliationProcessor.reconcile(...)`
call:

1. Compute the complete proposed result set (settlement observations and
   ledger data are both immutable, so reading them first is safe).
2. Atomically claim `(settlement_import_id, algorithm_version)`.
3. If the claim wins, insert every result in the same transaction, commit.
4. If the claim loses, read back and return the committed winning run as
   a replay — no results are inserted on this path.

**READ COMMITTED, not REPEATABLE READ**, and this is a correctness
requirement, not a preference: under REPEATABLE READ, a losing
transaction's snapshot is fixed at its own start, so its follow-up read
after losing the claim could still fail to see the winner's now-committed
row — the very row it needs to return as a replay. Under READ COMMITTED,
that follow-up read gets a fresh snapshot and reliably observes it. See
`ReconciliationProcessor`'s Javadoc for the full explanation.

### Performance

Three bulk queries per run regardless of row count — settlement
observations (`first_import_id = ?`), `ledger_transaction` rows
(`id = ANY(:ids)`), `ledger_entry` rows (`transaction_id = ANY(:ids)`) —
never one query per observation (`LedgerDataLoader`). Result-set
retrieval is paginated (`ORDER BY` the originating observation's
`source_row_number` — a stable, meaningful, deterministic order reflecting
the CSV file's own row order), so returning results never requires
holding a whole run in memory again. Bounded by Task 14's own 10,000-row
import limit — the same order of magnitude Task 14 itself already holds
in memory for one import. No outbox publisher, Kafka consumer, or
producer tuning of any kind — this task has no Kafka involvement at all.

## Reliability Hardening (implemented, Task 16)

Task 16 is a **test-only** hardening task — a reliability audit of Tasks
1–15's existing concurrency, idempotency, rollback, outbox, Kafka,
settlement, and reconciliation test coverage, closing five genuine gaps
the audit found. **No production code was changed**: every new test
passed against the existing implementation, confirming (rather than
correcting) the guarantees below. See `docs/TEST_STRATEGY.md`'s
"Reliability and Concurrency Hardening Tests" section for the exact new
tests and what each one proves.

### The reliability model, stated precisely

LedgerGuard's reliability model is, and after Task 16 remains:

- **Atomic PostgreSQL transactions** for every financial write — a
  deposit or transfer's ledger entries, materialized balance updates,
  idempotency record, and outbox event all commit or roll back together,
  in one transaction (Tasks 4/5/10/11).
- **Deterministic row locking** (ascending account-id order, never by
  role) prevents avoidable deadlocks between concurrent operations that
  touch the same two accounts in different orders (Tasks 4/5, "Deterministic
  Lock Ordering" above).
- **Idempotent command handling** via a PostgreSQL transaction-scoped
  advisory lock, keyed by the caller's Idempotency-Key — the database,
  not a JVM-local lock or cache, arbitrates concurrent identical/
  conflicting requests (Task 10, "Idempotency" above).
- **Transactional outbox** for reliable event publication — an event is
  never published without its business transaction having already
  committed, and a business transaction that rolls back leaves no
  publishable event (Task 11).
- **At-least-once Kafka delivery** — Task 12's producer never guarantees
  a message is sent exactly once; a crash between broker acknowledgement
  and marking `published_at` can cause a redelivery on retry (Task 12,
  "Kafka Publishing" above).
- **Idempotent consumer processing** — Task 13's consumer makes the
  *PostgreSQL-side effect* of processing a given `eventId` effectively
  once, via the same atomic-claim pattern as idempotency (Task 13,
  "Kafka Consumption" above).
- **Immutable settlement and reconciliation history** — every settlement
  and reconciliation table is append-only, enforced by database triggers,
  never by application-level convention alone (Tasks 14/15).

**This is explicitly *not* a claim of distributed exactly-once delivery
across PostgreSQL and Kafka** — that combination remains at-least-once
delivery made effectively-once at the PostgreSQL layer for a given event,
exactly as Tasks 12/13 originally documented. Task 16 does not change
this model; it adds direct proof that the model holds under real
concurrent load, forced failures, and (for the outbox) a genuinely new
component instance — not a stronger guarantee than what Tasks 1–15
already built.

### What Task 16 tested that was new

- A true concurrent race for transfer idempotency (previously only
  proven for deposit).
- A single mixed workload — deposits and transfers, including
  opposite-direction pairs, across several shared accounts at once —
  followed by a global consistency audit computed directly from
  committed `ledger_entry` rows (never predicted from HTTP responses,
  and never assuming every submitted transfer succeeds).
- A genuine PostgreSQL-level forced failure for settlement import
  (previously only business-logic-triggered rollbacks were tested there).
- Direct proof that a brand-new, non-Spring-managed `OutboxPublisher`
  instance — sharing no in-memory state with the application's own bean —
  can claim and publish a pending event, rather than only inferring this
  from the class having no instance fields.

### What Task 16 does not claim

- Not every possible failure mode has been eliminated — only the
  specific scenarios above are proven correct, against real PostgreSQL
  and (where broker behavior matters) real Kafka, never mocked for a
  guarantee that depends on real database or broker behavior.
- No new retry framework, dead-letter design, cache, distributed lock, or
  automatic broad retry around a financial transaction was added — none
  was needed, since the audit found no correctness defect to justify one.

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

## Authentication and Authorization (implemented, Task 17)

Stateless JWT authentication and CUSTOMER/OPERATIONS ownership-based
authorization, added in Phase 3 per `CLAUDE.md`'s explicit "Authentication
and authorization are introduced only in Phase 3."

**Two layers of defense.** `security.SecurityConfig`'s
`SecurityFilterChain` is the coarse, first layer: URL/method/role rules
(`hasAuthority("CUSTOMER")`, `hasAuthority("OPERATIONS")`,
`hasAnyAuthority(...)`) gate every `/api/v1/**` and `/actuator/**` path
except the public ones (`POST /api/v1/auth/token`, `/actuator/health/**`,
the static UI, Swagger/OpenAPI). This alone is insufficient: it cannot
express *ownership* (which specific account a CUSTOMER may touch), and a
hypothetical caller invoking a service directly would bypass it entirely.
The second, mandatory layer is service-layer enforcement:
`AccountService`, `AccountQueryService`, `DepositService`,
`TransferService`, `SettlementImportService`, and `ReconciliationService`
each independently re-verify the role (via the shared
`security.AuthorizationSupport.requireRole`, which throws a plain Spring
Security `AccessDeniedException` so it is caught by the same
`ExceptionTranslationFilter`/`ApiAccessDeniedHandler` pair as a
filter-chain-level denial) and, for account-scoped operations, ownership
— so calling a service through any other entry point still cannot bypass
authorization.

**Authentication flow.** `POST /api/v1/auth/token` authenticates one of
the fixed, configuration-backed identities
(`ledgerguard.security.users[]` — username + BCrypt hash + role; never a
plaintext password, never database-backed) and returns a short-lived
(900s default) HS256 JWT (`security.JwtIssuer`) with `sub` = username,
`roles` = the one server-assigned role, `iss`/`aud` fixed to
`ledgerguard`/`ledgerguard-api`, and `iat`/`exp`. Unknown username and
wrong password return the identical 401 status and message
(`InvalidCredentialsException`, "Invalid username or password.") — the
lookup runs a full `BCryptPasswordEncoder.matches` against a fixed dummy
hash even for a nonexistent username, so the two cases cost the same and
never trivially differ in timing. `security.SecurityConfig` also
publishes the `JwtDecoder` every subsequent request is validated against:
signature (HS256, the same symmetric key), issuer, audience, and
expiration (`JwtTimestampValidator`, with a small configurable clock-skew
allowance). Authorities are read from the token's own `roles` claim via
an explicitly configured `JwtGrantedAuthoritiesConverter` (claim name
`roles`, no prefix) — deliberately not Spring's default `scope`/`SCOPE_`
convention, since these are LedgerGuard's own roles, not OAuth2 scopes.

**Startup validation.** `security.SecurityConfigurationValidator`
(`@PostConstruct`) fails application startup — not silently, not on
first request — if `ledgerguard.security` is misconfigured: duplicate or
blank usernames, a missing/invalid role, a password hash that is not a
syntactically valid BCrypt hash, a non-positive or excessive JWT
expiration/clock-skew, or a signing key that is missing, not valid
Base64, or decodes to fewer than 32 bytes (256 bits).

**Ownership.** `account.customer_subject` (Flyway V7) is the stable
owner of a `CUSTOMER` wallet, sourced exclusively from
`AuthenticatedPrincipal.subject()` (itself built from the validated JWT's
`sub`+`roles` claims) — never from request JSON, which has no field for
it. A CUSTOMER may read (`GET .../balance`, `.../transactions`),
deposit into, or transfer *from* only an account whose
`customer_subject` equals their own subject; OPERATIONS may read any
account but never deposits or transfers. A transfer's *destination* is
never ownership-checked — a CUSTOMER may send funds to any valid
CUSTOMER_WALLET account they do not own, and the response never exposes
that account's balance (`TransferResponse` simply has no such field).
Every ownership violation returns 404 — `AccountNotFoundException`,
reused as-is — deliberately extending this project's own pre-existing
SYSTEM-account-as-404 precedent (an id that is valid-but-restricted is
indistinguishable from one that does not exist), rather than introducing
a new 403-for-ownership policy that would leak the id's existence.
Blanket, non-resource-specific role denials (e.g. OPERATIONS attempting
`POST /api/v1/accounts`) return 403 instead, since no specific resource
id is involved and 403 discloses nothing extra. See
`docs/DATA_MODEL.md`'s "Customer Ownership and Principal-Scoped
Idempotency (V7)" section for the exact schema and backfill.

**Idempotency isolation.** `idempotency_key` uniqueness is now
`(principal_subject, idempotency_key)`, not `idempotency_key` alone (V7)
— see `docs/DATA_MODEL.md`. `IdempotencyService.execute(...)` takes the
authenticated principal's subject explicitly and both stores and looks
up by it. Critically, for both `DepositService.deposit(...)` and
`TransferService.transfer(...)`, the sequence is: (1) validate the role;
(2) load and validate ownership of the primary/source account,
*unlocked*, strictly before any idempotency work; (3) only then claim or
replay the principal-scoped idempotency record; (4) execute the
financial transaction only for a genuinely new key. This means
authorization is checked before any stored response can be returned, and
a different principal reusing another principal's exact key string never
even reaches the idempotency table for an account they do not own —
independent of (and in addition to) the schema-level scoping itself. The
advisory lock key (`IdempotencyCommand.advisoryLockId`) is likewise
principal-aware, so two different principals choosing the same literal
key string never unnecessarily serialize against each other.

**A subtle correctness bug found and fixed during this task:** the new
unlocked ownership pre-check loads the `Account` entity via
`AccountRepository.findById(...)`, which places it in the JPA
persistence context (first-level cache). Left there, `doDeposit`'s/
`doTransfer`'s later `@Lock(PESSIMISTIC_WRITE)` query returns that same,
by-then-stale managed instance instead of re-reading the just-locked
row's true balance — Hibernate does not overwrite an already-managed
entity's field values from a subsequent query result by default. This
silently reintroduced the exact lost-update race Tasks 4–5's
deterministic row locking was built to prevent, and was caught
empirically by the project's own existing concurrent-deposit/transfer
tests (a 20-way concurrent deposit test lost the overwhelming majority
of its updates). The fix: `DepositService`/`TransferService` explicitly
call `entityManager.detach(account)` immediately after the ownership
check, forcing a fresh, correctly-locked read.

**401/403 response shape.** Spring Security's filter chain runs before
Spring MVC's `@RestControllerAdvice`, so `common.GlobalExceptionHandler`
cannot produce a body for a filter-chain-level rejection.
`security.JwtAuthenticationEntryPoint` (401) and
`security.ApiAccessDeniedHandler` (403) instead use a shared
`security.SecurityApiErrorSupport` to write the identical `ApiError`
envelope `GlobalExceptionHandler` uses elsewhere — same five fields, no
stack trace, no JWT/SQL/credential detail. A genuinely unmapped path
*outside* `/api/v1/**` (e.g. a random top-level typo) still falls through
the filter chain's final `permitAll()` unauthenticated and reaches Spring
MVC's existing (Task 16-verified) `NoResourceFoundException` → 404
handling. A path *under* `/api/v1/**` that happens not to correspond to
any real route is a different case: the whole namespace requires
authentication, so an unauthenticated request to it is rejected as 401
before Spring MVC's routing — and therefore route (non-)existence — is
ever reached. This is intentional: it means an unauthenticated caller can
never distinguish "this API path doesn't exist" from "this API path
exists but I'm not authenticated," which is the stricter, more
information-hiding behavior of the two.

**Configuration.** `ledgerguard.security.jwt.signing-key` has no default
in `application.yml` — it must come from the `JWT_SIGNING_KEY`
environment variable in any real environment (see `.env.example`);
`SecurityConfigurationValidator` fails startup if it is absent, malformed,
or too short. The `test` profile (`application-test.yml`) configures a
fixed, clearly test-only signing key and three isolated test identities
(`test-customer-a`, `test-customer-b`, `test-operations`) — completely
separate from the demo/dev identities, so no integration test ever
authenticates as `demo-customer`/`demo-operations`. `test-customer-a` and
`test-customer-b` exist specifically so cross-principal ownership and
idempotency-isolation tests have two real, independently authenticatable
CUSTOMER principals.

**UI.** The demo UI's access token lives only in an in-memory JavaScript
variable (`src/main/resources/static/app.js`), attached automatically to
every request via the existing centralized `apiRequest()` wrapper —
never written to `localStorage`, `sessionStorage`, a URL, or a log.
Reloading the page always signs the user out. The existing
recent-account-id/recent-import-id `localStorage` convenience is
unaffected.

**Scope.** No external OAuth/IdP, no refresh tokens, no cookie-based
sessions, no user registration or database-backed identity store, no
distributed/Redis-backed session or authorization cache. CSRF is
disabled — justified, not overlooked: this is a stateless bearer-token
API with no cookie-based session to fixate. Production hardening of the
Swagger/OpenAPI/actuator public surface is explicitly deferred to a
later task.
