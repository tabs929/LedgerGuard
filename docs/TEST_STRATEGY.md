# Test Strategy

> **Status: all Phase 1 test-writing tasks are implemented, plus Task 10
> (idempotency), Task 11 (transactional outbox), Task 12 (Kafka
> publishing), Task 13 (Kafka consumption and duplicate-event
> protection), Task 14 (settlement CSV import), Task 15 (settlement
> reconciliation), and Task 16 (reliability, failure, and concurrency
> hardening) — every task in Phase 2 is now complete — plus Task 17
> (stateless JWT authentication and ownership-based authorization, the
> first Phase 3 task) — and every test runs
> automatically in CI (Task 9) on every push/PR.** The connectivity smoke
> test (Task 1), schema-verification tests (Task 2), account creation's
> tests (Task 3), deposit's ledger-balance/rollback/concurrency tests
> (Task 4), transfer's ledger-balance/conservation/insufficient-funds/
> rollback/deadlock-avoidance tests (Task 5), the balance/history read
> tests (Task 6), the global error-envelope/validation/leakage tests
> (Task 7), the OpenAPI document/schema-accuracy tests (Task 8), the
> idempotency header/replay/conflict/rollback/concurrency tests (Task 10),
> the outbox event/replay/rollback/constraint/immutability tests
> (Task 11), the Kafka publisher tests (Task 12), the Kafka consumer
> validation/duplicate/conflict/rollback tests (Task 13, real PostgreSQL
> **and** real Kafka Testcontainers), the settlement CSV
> parsing/hashing/duplicate/conflict/concurrency/financial-non-effect
> tests (Task 14, real PostgreSQL, no Kafka), the settlement
> reconciliation matching/classification/replay/concurrency/
> financial-non-effect tests (Task 15, real PostgreSQL, no Kafka), and
> the mixed-workload/global-consistency, transfer-idempotency-race,
> settlement-forced-rollback, and outbox-new-instance-recovery hardening
> tests (Task 16, zero production changes required) all
> exist (see "Currently
> Implemented" below) and all run in `.github/workflows/ci.yml` (see "CI"
> below). Only `GET /api/v1/accounts/{id}` remains untested, since that
> plain-lookup endpoint was never assigned to any task and so still
> doesn't exist.

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

`OutboxPublisherIntegrationTest` (Task 12) additionally starts an
isolated `apache/kafka:3.8.0` container (KRaft, no ZooKeeper) the same
way, also via `@ServiceConnection`. Every other integration test suite
disables the Kafka publisher entirely
(`ledgerguard.outbox.publisher.enabled=false`, set once in the shared
`application-test.yml`) specifically so it never attempts a Kafka
connection — no `NewTopic` bean and no `@Scheduled` poller are even
registered in those suites' contexts. No Kafka broker behavior is ever
mocked in the one suite that does start Kafka.

`LedgerEventConsumerIntegrationTest` (Task 13) likewise starts its own
isolated `apache/kafka:3.8.0` container and re-enables both
`ledgerguard.outbox.publisher.enabled` and
`ledgerguard.inbox.consumer.enabled` (both `false` by default in
`application-test.yml`, for the same reason as above). No PostgreSQL
transaction/locking behavior and no Kafka consumption is ever mocked in
this suite either.

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
404-for-SYSTEM-account API behavior. These required the future domain
service — implemented for deposits in Task 4, see "Deposit Tests" below.

## Deposit Tests (implemented, Task 4)

`DepositIntegrationTest` (18 tests) verifies deposit processing at the HTTP
boundary (`TestRestTemplate`) and the persisted database state (direct
JDBC), against a Testcontainers-provisioned `postgres:16.4` instance:

- **Success + response shape:** a valid deposit returns 201 with the
  documented response fields.
- **Balanced double-entry proof:** exactly one `ledger_transaction`
  (`DEPOSIT`/`COMPLETED`) and exactly two `ledger_entry` rows are created —
  one `DEBIT` against the funding account, one `CREDIT` against the
  destination account, same amount, same currency, same `transaction_id`,
  `SUM(debits) == SUM(credits)` for the transaction.
- **Balance correctness:** both the customer wallet's and the funding
  account's materialized balances increase by exactly the deposit amount; a
  second deposit accumulates correctly on top of the first.
- **Validation/rejection tests:** zero amount, negative amount, missing
  amount, malformed amount, excess fractional precision (>4 digits), an
  amount exceeding 15 integer digits, a nonexistent destination account, the
  `EXTERNAL_FUNDING` account itself as destination, a directly-inserted
  non-USD customer wallet as destination, and an attempt to set protected/
  unknown JSON fields (`transactionId`, `entryType`, `fundingAccountId`,
  `newBalance`, etc.) — each is rejected with the documented status code
  and persists nothing.
- **Rollback proof:** `databaseOverflowMidTransactionRollsBackTheEntireDeposit`
  pre-seeds an account balance near `NUMERIC(19,4)`'s precision limit via
  direct SQL, then deposits an amount that pushes the balance `UPDATE` over
  that limit — a genuine PostgreSQL `numeric field overflow`, not a
  synthetic throw — and asserts no transaction, no entries, and no balance
  change survive; the error response body contains no SQL/ORM internals.
- **Immutability re-check:** `UPDATE`/`DELETE` against the `ledger_transaction`
  and `ledger_entry` rows a real deposit just created are still rejected by
  the Task 2 triggers.
- **Concurrency (real PostgreSQL locking, no mocks, no Java-only
  synchronization):** `concurrentDepositsIntoSameWalletDoNotLoseUpdates`
  fires 20 concurrent HTTP deposit requests at the same customer account
  via an `ExecutorService`, and asserts the final customer and funding
  balances equal their pre-batch starting balances plus the sum of all 20
  deposits, with an exactly-20-row ledger-entry count for that account —
  proving PostgreSQL's row locking (not application code) serializes the
  concurrent balance updates correctly.

## Transfer Tests (implemented, Task 5)

`TransferIntegrationTest` (28 tests) verifies transfer processing at the
HTTP boundary (`TestRestTemplate`) and the persisted database state (direct
JDBC), against a Testcontainers-provisioned `postgres:16.4` instance:

- **Success + response shape, balanced double-entry proof, balance
  correctness:** same shape of proof as deposits (one `TRANSFER`
  transaction, two entries — source `DEBIT`, destination `CREDIT` — equal
  positive amounts, same currency, same `transaction_id`), plus a
  **conservation check** specific to transfers: the *combined* source +
  destination balance is asserted unchanged before/after (money moved
  internally, none entered or left the ledger). Sequential transfers
  accumulate correctly; a transfer of the full source balance succeeds and
  leaves it at exactly zero.
- **Validation/rejection tests:** zero/negative/missing/malformed amount,
  excess precision, an amount exceeding 15 integer digits, missing
  source/destination id, a nonexistent source or destination account,
  self-transfer, insufficient funds, `EXTERNAL_FUNDING` as source or as
  destination, directly-inserted non-USD source and non-USD destination
  wallets, and protected/unknown JSON fields — each rejected with the
  documented status and persists nothing.
- **Rollback proof:** the same real-database-overflow technique used for
  deposits (pre-seed the destination near `NUMERIC(19,4)`'s ceiling via
  direct SQL, then transfer an amount that overflows it), asserting no
  transaction, no entries, and no balance change on either account survive.
- **Immutability re-check:** `UPDATE`/`DELETE` against rows a real transfer
  just created are still rejected.
- **Regression:** a deposit followed by a transfer produces the expected
  balances on both accounts, and the `EXTERNAL_FUNDING` balance is
  unaffected by the transfer.
- **Concurrency (real PostgreSQL locking, no mocks, no Java-only
  synchronization, bounded timeouts so a real deadlock would fail the test
  instead of hanging the build):**
  - `concurrentTransfersFromOneSourceDoNotOverspend` — 20 concurrent
    transfers of $10 each from a $100 source (only 10 are affordable);
    asserts exactly 10 succeed (201) and exactly 10 are cleanly rejected
    (422 insufficient funds, not lost/hung/silently-dropped requests), the
    final source balance is never negative, and both final balances equal
    the starting balances plus/minus exactly the successful transfers.
  - `concurrentOppositeDirectionTransfersCompleteWithoutLostUpdatesOrDeadlock`
    — 10 A→B and 10 B→A transfers fired concurrently between the same two
    accounts; asserts all 20 complete (proving the deterministic
    id-ordered locking prevents the deadlock that source-then-destination
    locking would risk) and both balances return to their starting values.

## Account Balance and Transaction History Tests (implemented, Task 6)

`AccountQueryIntegrationTest` (31 tests) verifies the two read endpoints at
the HTTP boundary (`TestRestTemplate`), against a Testcontainers-provisioned
`postgres:16.4` instance:

- **Balance:** a new wallet reports exactly `0.0000`; deposits and
  transfers change the returned balance by exactly the expected amount
  (including a multi-operation sequence); the response contains exactly
  the three approved fields (`accountId`, `balance`, `currency` — no more,
  no fewer); the returned `BigDecimal` retains its full stored scale
  (`4`, not silently rounded to `2`); a nonexistent account and a `SYSTEM`
  account (`EXTERNAL_FUNDING`) both return 404; a malformed UUID returns
  400 (a request-shape error, not a not-found error — see
  `docs/API_SPEC.md`); repeated `GET`s create no `ledger_transaction`/
  `ledger_entry` rows and never change the balance they just read.
- **Transaction history:** a new account's history is the documented empty
  page (`content: []`, `totalElements: 0`, `totalPages: 0`, default
  `page`/`size`); a deposit appears as exactly one `CREDIT` item with the
  correct amount/currency/transaction id; a transfer appears as a `DEBIT`
  in the source's history and a `CREDIT` in the destination's, both
  referencing the same `transactionId`; a mixed sequence of deposits and
  transfers across two accounts produces exactly the expected item count
  for each account, with entries belonging only to the *other* account
  never appearing; ordering is verified newest-first across three
  time-separated deposits; `SYSTEM`/nonexistent/malformed-UUID behave the
  same as the balance endpoint; reads create no ledger rows and change no
  balance.
- **Pagination:** default `page=0, size=20`; explicit `page`/`size` values
  produce correct `totalElements`/`totalPages` and item counts per page,
  with every item across all requested pages appearing exactly once (no
  duplicates, no gaps) — proven by collecting all `transactionId`s across
  three pages of a 25-item history and asserting the resulting set has
  exactly 25 distinct entries; `size=1` and `size=100` (the documented
  bounds) are both accepted; `page=-1`, `size=0`, `size=-5`, `size=101`,
  and non-numeric `page`/`size` values are all rejected with 400.
- **Cross-feature regression:** a deposit-then-transfer sequence, with
  Task 6 reads interleaved between every write, still leaves the transfer's
  two ledger entries balanced (`SUM(debits) == SUM(credits)`, checked
  directly via JDBC); `EXTERNAL_FUNDING`'s balance is unaffected by any
  Task 6 read; the Task 2 immutability triggers still reject `UPDATE`/
  `DELETE` against rows a real deposit created.

## Global Error Handling Tests (implemented, Task 7)

`GlobalExceptionHandlingIntegrationTest` (37 tests) verifies the
centralized error envelope at the HTTP boundary, against a
Testcontainers-provisioned `postgres:16.4` instance:

- **Envelope shape:** a representative error response contains exactly
  the five documented fields (no more, no fewer); `status` matches the
  actual HTTP status; `error` matches the HTTP reason phrase; `path`
  matches the request URI; `timestamp` parses as a valid ISO-8601 instant;
  the response `Content-Type` is `application/json`.
- **Leakage:** across a 404, a 400, and a 422 response, the body is
  checked against a list of forbidden substrings — Java/Jackson/Hibernate/
  PostgreSQL/HikariCP class-name fragments, `SQLState`, `Caused by`,
  stack-trace frames, and internal table/column/constraint-name fragments
  (`ledger_entry`, `chk_`, `idx_`, raw SQL keywords) — none of which ever
  appear.
- **Request-shape validation (400):** missing/unknown/malformed fields and
  malformed JSON on account creation; missing/zero/negative/malformed/
  over-precision amounts and protected fields on deposits; missing
  source/destination/amount, a malformed UUID, zero/negative/over-precision
  amounts, and protected/unknown fields on transfers; a malformed path
  UUID on both balance and history; malformed, negative, zero, and
  over-100 pagination parameters.
- **Domain errors:** a nonexistent account is 404 on every endpoint that
  accepts an account id (balance, history, deposit, transfer); a `SYSTEM`
  account (`EXTERNAL_FUNDING`) is likewise 404 everywhere; an unsupported
  currency on account creation is 422; an invalid deposit/transfer
  destination is 404; insufficient funds is 422.
- **Consistency:** the same four endpoints' 404 responses (nonexistent
  account, on balance/history/deposit/transfer) are each checked to have
  the identical envelope field set and identical `status`/`error` —
  proving one shared mechanism produces every one of them, not four
  independently-coded ones that happen to agree today.
- **Atomicity regression:** a rejected deposit and a rejected
  (insufficient-funds) transfer both create no `ledger_transaction`/
  `ledger_entry` rows and change no balance; a deliberately forced
  mid-transaction database failure (the same `NUMERIC(19,4)`-overflow
  technique from Tasks 4–5, re-run here to confirm the new global handler
  didn't change the guarantee) still rolls back completely and still
  leaks nothing in its 500 response.
- **Success regression:** account creation, deposit, and transfer still
  succeed and conserve funds; history ordering is still newest-first with
  the `id` tie-break; pagination defaults/bounds are unchanged
  (`page=0`/`size=20`, `1..100`); the Task 2 immutability triggers still
  reject `UPDATE`/`DELETE`.

## OpenAPI Documentation Tests (implemented, Task 8)

`OpenApiDocumentationIntegrationTest` (24 tests) verifies the generated
OpenAPI document and Swagger UI against the real, running application. It
runs against a real PostgreSQL 16.4 Testcontainer like every other test in
this project — the application requires a working datasource to start at
all (JPA + Flyway are mandatory auto-configuration), so there is no
database-free way to boot the context here:

- **Availability:** `/v3/api-docs` returns 200 with `application/json`;
  the document declares OpenAPI `3.x`; `info.title`/`info.version`/
  `info.description` are present and the description covers every Phase 1
  capability; `/swagger-ui/index.html` (and the `/swagger-ui.html`
  redirect) both return 200 with `text/html`.
- **Endpoint coverage:** all five implemented paths appear with the
  correct HTTP method; plain `/api/v1/accounts/{id}` is absent as its own
  path template; no undocumented `/api/v1/...` path exists at all (a
  generic loop over every path fails the test if one appears that isn't
  in the known set of five — this is what would catch a Task 9+ endpoint
  leaking in early).
- **Schema accuracy:** every request/response schema exists with the
  correct `required` field sets; UUID fields declare
  `type: string, format: uuid`; every monetary field
  (`amount`/`balance`/`newBalance`) declares `type: string` with no
  `float`/`double` format (proving decimal, not floating-point,
  representation); the `entryType` enum matches `LedgerEntryType` exactly;
  no request schema contains a protected/server-controlled field name
  (`id`, `balance`, `accountCategory`, etc. — checked generically, not
  field-by-field); no schema name or the schemas map itself references a
  JPA entity, a Hibernate proxy, or `PageImpl`; the `ApiError` schema's
  properties are exactly the five documented fields.
- **Response documentation:** documented success/error status codes match
  `docs/API_SPEC.md`/Task 7 exactly per endpoint; every response's content
  map has exactly one key, `application/json`; the transaction-history
  200 response resolves (by following its `$ref`) to a schema whose
  properties are exactly `{content, page, size, totalElements,
  totalPages}` — proving the custom envelope, not Spring Data's `Page` —
  with `content[]` items themselves `$ref`-ing `TransactionHistoryItem`
  (proving the generic type parameter was actually resolved, not erased);
  `page`/`size` parameters document the exact default/min/max contract;
  the operation description names the exact ordering
  (`created_at DESC` + tie-breaker).
- **Safety:** the full raw document is checked against the same kind of
  forbidden-substring list used in `GlobalExceptionHandlingIntegrationTest`
  (Java/Jackson/Hibernate/PostgreSQL class-name fragments, `SQLState`,
  internal table names, `password`/`credential`, a local port-style
  hostname string); no `components.securitySchemes` and no top-level
  `security` array exist; no request schema or path exposes a
  client-suppliable "funding"/"system" account field or path segment.
- **Regression:** a full create-account → deposit → balance → history
  sequence still succeeds through real HTTP with the documentation layer
  active, a 404 still returns the unchanged Task 7 envelope, and the
  Task 2 immutability triggers still reject `UPDATE` against a row a real
  deposit just created.

## Idempotency Tests (implemented, Task 10)

`IdempotencyIntegrationTest` (21 tests) verifies `Idempotency-Key` handling
for both protected endpoints at the HTTP boundary (`TestRestTemplate`) and
the persisted database state (direct JDBC), against a
Testcontainers-provisioned `postgres:16.4` instance. No PostgreSQL
concurrency is mocked; no H2; no local Docker Compose database; no
`Thread.sleep` used as a correctness mechanism — concurrency assertions use
`ExecutorService.invokeAll`/`Future.get` with bounded timeouts, so a real
deadlock or hang fails the test instead of blocking the build indefinitely.

- **Header validation:** a missing header (400, both deposit and transfer),
  a blank header, a 129-character header, and a header containing a space
  or a slash are all rejected (400); a 128-character key and a key using
  every allowed punctuation character (`. _ : -`) are both accepted.
- **Sequential replay:** an identical retry (same key, same body) returns
  the exact original status and body, creates no new `ledger_transaction`/
  `ledger_entry` rows, and changes the balance only once; a retry with a
  numerically-equivalent but differently-formatted amount (`"100"` then
  `"100.00"`) replays the same original transaction rather than treating
  them as different commands.
- **Same-operation conflict:** reusing a deposit key with a different
  amount, a different destination account, or a different (but otherwise
  valid) currency all return 409 and change no balance; reusing a transfer
  key with a different destination behaves the same way.
- **Cross-operation conflict:** a key first used for a deposit, then
  replayed against `/transfers` (and vice versa), returns 409 and performs
  no financial write in either direction.
- **Rollback / key not consumed by failure:** a deposit against a
  nonexistent account, and a transfer rejected for insufficient funds, both
  leave zero `idempotency_key` rows for that key — the same key then
  succeeds normally once retried with corrected data (a valid account, or a
  funded source). A genuine database-level failure (the same
  `NUMERIC(19,4)`-overflow technique used in Tasks 4/5) rolls back the
  financial write and leaves no `idempotency_key` row either — the key
  succeeds on a later retry once the underlying condition is fixed.
- **Concurrency (real PostgreSQL advisory locking, no mocks, no
  application-level synchronization):**
  - `simultaneousIdenticalDepositRequestsCommitExactlyOnce` — 15 concurrent
    requests with the same key and the same body; asserts every response is
    201 with an identical body, exactly one `ledger_transaction` and one
    `idempotency_key` row exist afterward, and the balance moved by exactly
    one deposit's worth.
  - `simultaneousConflictingDepositRequestsWithTheSameKeyProduceExactlyOneWinner`
    — 16 concurrent requests sharing one key, split between two different
    amounts; asserts every response is either 201 (all with an identical
    body — the single winning command) or 409, that the two counts sum to
    16, that exactly one `idempotency_key` row exists, and that the final
    balance reflects exactly one of the two candidate amounts, never both
    and never neither.

## Outbox Tests (implemented, Task 11)

`OutboxIntegrationTest` (29 tests) verifies the transactional outbox at
the HTTP boundary (`TestRestTemplate`) and the persisted database state
(direct JDBC), against a Testcontainers-provisioned `postgres:16.4`
instance. No PostgreSQL behavior is mocked; concurrency assertions use
`ExecutorService.invokeAll`/`Future.get` with bounded timeouts, never
`Thread.sleep`, as the correctness mechanism.

- **Deposit/transfer success:** a successful deposit (and, separately, a
  successful transfer) creates exactly one `outbox_event` row keyed by the
  new `ledger_transaction.id`, with the correct `event_type`/
  `aggregate_type`/`schema_version`/`published_at IS NULL`, and a payload
  containing exactly the approved fields — `amount` as a four-decimal JSON
  string, `currency` uppercase, `occurredAt` matching the ledger
  transaction's own `created_at`, and (for deposits) no occurrence of the
  internal `EXTERNAL_FUNDING` account id anywhere in the stored payload.
- **Idempotent behavior:** an identical retry (including
  numerically-equivalent amount formatting) leaves exactly one row for the
  same transaction; a conflicting retry (different amount/account, or the
  same key reused against the other endpoint) creates no additional row
  and returns 409; concurrent identical requests sharing one key create
  exactly one row; concurrent requests sharing one key but split across
  two different amounts create a row only for whichever single command
  actually won (the other requests all receive 409).
- **Rollback behavior:** validation failures, a nonexistent account,
  insufficient funds, and a forced genuine database-level failure (the
  same `NUMERIC(19,4)`-overflow technique used in Tasks 4/5/10) all leave
  no outbox row. A forced outbox-insertion failure —
  `forcedOutboxInsertionFailureRollsBackTheWholeOperationAndKeySucceedsAfterCorrection`
  adds a real `CHECK (1 = 0) NOT VALID` constraint directly to
  `outbox_event` for the duration of the test (a genuine, deterministic
  PostgreSQL-level failure, not a mock — chosen because the outbox row's
  own `aggregate_id` is a randomly-generated UUID that can't be predicted
  in advance to engineer a natural unique-constraint collision) — proves
  the ledger transaction, its entries, both balance changes, and the
  Task 10 idempotency record all roll back together with the outbox
  insert; after the constraint is dropped, the same `Idempotency-Key`
  succeeds and creates exactly one outbox row.
- **Constraint behavior:** a direct duplicate `INSERT` against an existing
  transaction id/event type is rejected by `uq_outbox_event_identity`; an
  invalid `aggregate_type`, an invalid `event_type`, a non-positive
  `schema_version`, and a non-object JSON payload are each rejected by
  their respective `CHECK` constraint; an `aggregate_id` with no matching
  `ledger_transaction` row is rejected by the foreign key; a direct
  `UPDATE` of `event_type` or `payload` is rejected (immutability); a
  direct `DELETE` is rejected; `published_at` can move from `NULL` to
  `now()` exactly once, and any further change to it (clearing it or
  overwriting it again) is rejected.
- **Migration behavior:** `V1`, `V2`, and `V3` all apply successfully from
  an empty schema; `outbox_event`'s constraints, the pending-event partial
  index, and both immutability triggers all exist; `V1`/`V2` objects
  (`chk_account_taxonomy_combination`, `uq_idempotency_key`) are still
  present and unchanged.

`OutboxEventFactoryTest` (6 unit tests, Mockito — `OutboxEventRepository`
mocked, a real Jackson 3 `JsonMapper` used for actual serialization) covers
what doesn't need a database: the deposit and transfer payloads contain
exactly the approved fields, `amount` always serializes as a fixed
four-decimal JSON string (never a bare number, even for a whole-number
amount), `occurredAt` serializes as ISO-8601 UTC and matches the `Instant`
passed in, `schemaVersion` is `1` for both event types, and two events
built from identical inputs still get independently random `eventId`s
(never derived from a hash or a mutable value).

## Outbox Publisher Tests (implemented, Task 12)

`OutboxPublisherIntegrationTest` (17 tests) verifies Kafka publishing of
pending `outbox_event` rows against **both** a real PostgreSQL 16.4
Testcontainer and a real `apache/kafka:3.8.0` Testcontainer (KRaft, no
ZooKeeper) — no Kafka broker acknowledgement is ever mocked. The shared
`application-test.yml` publisher-disabled default is overridden back to
`enabled=true` for this class only, alongside a deliberately long
`poll-delay-millis` (an hour): almost every test calls
`OutboxPublisher.publishIfPending(eventId)` directly for deterministic,
immediate behavior, rather than racing a live wall-clock scheduler against
its own assertions — exactly one test exercises the scheduler's own
polling method directly to prove its candidate-selection-and-delegation
logic, without depending on `@Scheduled` actually firing on a timer
(Spring's own scheduling infrastructure is not this project's to
re-prove). All waits that do remain (draining the real Kafka consumer
used to inspect produced records) are bounded via Awaitility, never
`Thread.sleep` as the correctness mechanism.

- **Topic and producer configuration:** the configured topic exists with
  the configured partition count (verified via a real `Admin` client); a
  published record's key equals the ledger transaction id, the value is
  valid JSON matching the stored payload field-for-field, and neither the
  key, the value, nor any header ever contains the request's raw
  `Idempotency-Key`.
- **Deposit/transfer publication:** a successful deposit (and,
  separately, a transfer) yields exactly one Kafka record with the
  correct `eventType`, key, and payload fields (four-decimal `amount`,
  uppercase `currency`), and `published_at` becomes non-null only after
  that record is produced; financial and idempotency state are unchanged
  by publication.
- **Idempotency behavior:** an identical retry (including
  numerically-equivalent formatting), a same-operation conflict, and a
  cross-operation conflict all still produce exactly the one record the
  original successful request's single outbox row accounts for — never a
  second one.
- **Failure behavior:** a real, unreachable-broker `OutboxPublisher`
  instance (a genuine `KafkaTemplate` pointed at an address nothing
  listens on — a real network-level failure, not a stub) leaves
  `published_at` `NULL` and changes no financial or idempotency state; the
  same event then publishes successfully once a working publisher is used
  instead; a failing candidate immediately followed by a different,
  healthy candidate in the same catch-and-continue shape
  `OutboxPublisherScheduler` itself uses proves one failure never blocks
  a later candidate.
- **Multi-instance/concurrency behavior:** two publisher calls racing the
  same pending event (via an `ExecutorService`, bounded `invokeAll`/
  `get` timeouts) produce exactly one Kafka record and one `published_at`
  transition; six distinct pending events published concurrently all
  succeed with no deadlock.
- **Ordering and batching:** `findPendingCandidateIds` returns
  `outbox_event`'s own ids (never the ledger transaction id) in
  `created_at ASC, id ASC` order, respects a small requested limit, and
  excludes an already-published row.
- **Database trigger behavior:** after a real publish, the stored payload
  is byte-for-byte unchanged, and the same immutability/no-delete/
  `published_at`-transition triggers `OutboxIntegrationTest` (Task 11)
  already proved remain fully effective — a real publish is not a
  privileged path around them.

`OutboxPublisherPropertiesValidationTest` (7 unit tests, no Spring
context) covers the Jakarta Bean Validation constraints on every
`ledgerguard.outbox.publisher.*` property directly: the default
configuration is valid, and a blank topic or a non-positive partitions/
replication-factor/poll-delay/batch-size/send-timeout each produce a
violation.

## Kafka Consumer Tests (implemented, Task 13)

`LedgerEventConsumerIntegrationTest` (19 tests) verifies Kafka consumption
and duplicate-event protection against **both** a real PostgreSQL 16.4
Testcontainer and a real `apache/kafka:3.8.0` Testcontainer — no
PostgreSQL transaction/locking behavior and no Kafka broker/consumer
behavior is ever mocked. Both the Task 12 publisher and the Task 13
consumer are re-enabled for this class (both `false` by default in
`application-test.yml`).

A deliberate design choice shapes which tests go through the real Kafka
topic versus calling `LedgerEventProcessor` directly: a permanently
invalid or conflicting record is *never* acknowledged (see
`docs/ARCHITECTURE.md`'s "Kafka Consumption" section), so it retries on
its Kafka partition indefinitely by design. Producing even one or two
such records onto the real, shared 3-partition topic risked the default
key-hash partitioner landing a later test's legitimate record on the same
partition as an earlier test's permanently-stuck one — which would then
never be delivered and hang that later test. `LedgerEventValidator` is
already exhaustively unit-tested in isolation (`LedgerEventValidatorTest`,
below); this integration suite instead calls
`LedgerEventProcessor.process(...)` directly — still against this class's
real PostgreSQL Testcontainer, so the actual transactional behavior is
genuinely exercised — for every case that would otherwise retry forever.
Success and identical-duplicate cases (which always acknowledge and so
can never block a partition) go through the real topic and the real
`@KafkaListener` container throughout.

- **Migration/schema:** `V1`–`V4` all apply from an empty schema and
  Flyway validation succeeds; `processed_event` has exactly the expected
  columns, constraints (event type, schema version, payload-hash format,
  non-blank topic, non-negative partition/offset, the source-position
  uniqueness constraint), and both immutability triggers; `V1`–`V3`
  objects are confirmed still present and unchanged.
- **Consumer/topic configuration:** the consumer uses the configured
  topic and group id.
- **Deposit/transfer consumption (real topic, real listener):** a valid
  record of each type, produced directly to the topic, results in exactly
  one `processed_event` row with the correct `aggregate_id`, `event_type`,
  `schema_version`, a `payload_hash` matching `PayloadHasher.sha256Hex`
  of the exact produced value, and the real Kafka source
  topic/partition/offset — with no financial (`ledger_transaction`/
  `account`) row created anywhere.
- **Duplicate handling (real topic, real listener):** an identical record
  delivered twice at different offsets, and the same `eventId` delivered
  concurrently on two explicitly different partitions, both still result
  in exactly one `processed_event` row; two `LedgerEventProcessor`
  invocations racing the same `eventId` concurrently (via an
  `ExecutorService`, bounded `invokeAll`/`get` timeouts, real PostgreSQL,
  no JVM-local cache) also produce exactly one row.
- **Conflicting duplicate handling (direct processor calls — see above
  for why):** the same `eventId` reused with a different amount,
  `transactionId`, or `eventType` is rejected via
  `ConflictingEventException`, the original row's `payload_hash` is
  unchanged, and no second row is inserted.
- **Validation (direct processor calls against real PostgreSQL):**
  malformed JSON and a Kafka-key/`transactionId` mismatch both throw
  `LedgerEventValidationException` and create no `processed_event` row; a
  rejected record also leaves `ledger_transaction`, `outbox_event`, and
  `idempotency_key` completely unchanged — proving the "no downstream
  mutation" guarantee directly, not just by absence of a listed effect.
- **Transaction/rollback behavior:** a real, deterministic PostgreSQL
  failure — a `CHECK (1 = 0) NOT VALID` constraint added directly to
  `processed_event` for the duration of the test, the same technique
  `OutboxIntegrationTest`/`OutboxPublisherIntegrationTest` already use —
  proves the whole transaction rolls back cleanly (no row committed), and
  that the same event processes successfully once the constraint is
  removed.
- **End-to-end Task 11→12→13 flow:** a real deposit (and, separately, a
  real transfer) is submitted over HTTP, its outbox event is published by
  the real Task 12 scheduler, consumed by the real Task 13 listener, and
  results in exactly one `processed_event` row; replaying the identical
  HTTP request (same `Idempotency-Key`) creates no second `outbox_event`
  row and therefore nothing new for the consumer to process.

`LedgerEventValidatorTest` (28 unit tests, no Spring context, no Kafka, no
PostgreSQL) exhaustively covers the validation matrix directly against raw
JSON strings: structural rejection (malformed JSON, a JSON array/scalar/
`null`, a missing field, an unexpected field, a `null` field);
field-format rejection (invalid `eventId`/`transactionId` UUIDs, an
invalid `occurredAt`, an unknown `eventType`, an unsupported
`schemaVersion` — as an out-of-range integer, as a string, and as a
floating-point literal — a Kafka-key/`transactionId` mismatch, a JSON
numeric `amount`, an incorrectly-scaled or zero/negative `amount`, and a
lowercase or otherwise unsupported `currency`); and event-type-specific
rules (a deposit payload carrying a transfer-only field, a transfer
missing `sourceAccountId`, and a transfer with identical source and
destination accounts).

`PayloadHasherTest` (5 unit tests) proves the SHA-256 hashing is over the
exact UTF-8 string (a known test vector, plus that whitespace-only
differences change the hash). `ProcessedEventRecordTest` (6 unit tests)
covers the identical-vs-conflicting comparison directly, including that
source position is never part of the comparison.
`LedgerConsumerPropertiesValidationTest` (6 unit tests, no Spring context)
covers the Jakarta Bean Validation constraints on every
`ledgerguard.inbox.consumer.*` property.

## Settlement Import Tests (implemented, Task 14)

Unit tests (no Spring context, package `com.tarun.ledgerguard.settlement`):
- `Sha256Test` (4) — a known SHA-256 test vector, 64-lowercase-hex-char
  shape, identical-bytes-identical-hash, and that a byte-distinct
  (BOM-prefixed) file hashes differently from its BOM-stripped equivalent.
- `RowFingerprintTest` (5) — determinism, 64-lowercase-hex-char shape,
  that changing any single field changes the hash, that the
  length-prefixed canonical encoding resists field-boundary ambiguity
  (two field tuples whose naive concatenation would collide), and the
  exact length-prefix format.
- `SourceNormalizerTest` (3) — lowercasing, idempotence, and that two
  different casings of the same source normalize identically.
- `SettlementImportPropertiesTest` (6) — Jakarta Bean Validation on every
  `ledgerguard.settlement.import.*` property, direct against a
  `Validator`, no Spring context.
- `SettlementCsvParserTest` (35) — the full CSV-contract matrix: valid
  single/multi-row parsing, 1-based row numbering, LF and CRLF line
  endings, an optional UTF-8 BOM, quoted fields with embedded
  commas/escaped quotes/embedded newlines, ignored blank physical lines,
  empty file, header-only file, unknown/missing/duplicate/reordered
  header columns, missing/extra row values, malformed quoting, invalid
  UTF-8, row-limit enforcement while parsing, a repeated
  `external_reference` within one file, and every field's validation
  rule (blank/oversized/control-character `external_reference`,
  non-canonical `transaction_id`, scientific-notation/locale-formatted/
  wrong-scale/zero/negative `amount`, lowercase/unsupported `currency`,
  and an offset-less `settled_at`).

Integration tests (PostgreSQL 16.4 Testcontainers, never H2, never the
local Docker Compose database):
- `SettlementSchemaMigrationIntegrationTest` (20) — `V1`–`V5` all apply
  from an empty schema; `V1`–`V4` unchanged (same descriptions as before
  Task 14); both settlement tables' columns, primary keys, the unique
  file/row identity constraints, the absence of a foreign key from
  `settlement_record` to `ledger_transaction`, the foreign key to
  `settlement_import`, every `CHECK` constraint, and both tables'
  append-only `UPDATE`/`DELETE`-rejecting triggers.
- `SettlementImportIntegrationTest` (28) — HTTP-boundary tests via
  `TestRestTemplate` against `POST /api/v1/settlement-imports`
  (`multipart/form-data`), plus direct JDBC verification of persisted
  state: single/multi-row imports (201, correct counts), exact
  persisted-value/file-hash/row-hash verification, unknown and real
  (deposit) transaction UUIDs accepted without any comparison to the
  ledger's actual amount, exact-file replay (200, no new rows), a
  logically identical row inside a byte-distinct file (counted as a
  duplicate, not re-inserted), conflicting amount/currency/transaction-id/
  timestamp (all 409, whole import rolled back, original observation
  unchanged — the currency case plants its "existing" row directly via
  JDBC, since LedgerGuard supports only USD today and a currency conflict
  cannot otherwise be produced from two valid CSV rows), retry-after-
  conflict success, an invalid row creating no import or observation, a
  conflict in a file's *last* row rolling back every earlier row in the
  same file, financial and event-table non-effects (`account` row count
  and total balance, `ledger_transaction`, `ledger_entry`,
  `idempotency_key`, `outbox_event`, `processed_event` all unchanged
  before vs. after), request-level validation (empty file, header-only
  file, invalid row, blank source, unsupported content type,
  file-size-limit and row-count-limit rejection), and three real
  concurrency tests — two threads uploading the exact same file
  (exactly one import row, the loser gets a replay), two threads
  uploading byte-distinct files sharing one identical row (exactly one
  observation, both imports still complete), and two threads uploading
  conflicting rows (exactly one winner, one 409) — using
  `ExecutorService`/`CountDownLatch`-gated concurrent starts, never
  `Thread.sleep` as the correctness mechanism, proving real PostgreSQL
  constraint arbitration rather than request serialization.
- `SettlementImportDisabledIntegrationTest` (2) — a separate
  `@SpringBootTest` context with
  `ledgerguard.settlement.import.enabled=false`: every import request
  gets one explicit 503, and account creation on the same running
  instance is unaffected.
- `SettlementImportOpenApiIntegrationTest` (5) — the endpoint is
  documented; the multipart request body's file part is `type: string,
  format: binary` and `source` is a required string parameter; the
  documented status set is exactly `{200, 201, 400, 409, 413, 415}`;
  400/409 responses reference the shared `ApiError` schema; and no
  settlement list/get/update/delete/reconciliation endpoint exists.

Every PostgreSQL-only settlement test suite relies on
`ledgerguard.outbox.publisher.enabled`/`ledgerguard.inbox.consumer.enabled`
already being `false` under the `test` profile (`application-test.yml`,
Tasks 12–13) — none of them attempt a Kafka connection, and none of them
start a Kafka Testcontainer (settlement import has no Kafka dependency at
all).

## Settlement Reconciliation Tests (implemented, Task 15)

Unit tests (no Spring context, package `com.tarun.ledgerguard.reconciliation`):
- `ReconciliationMatcherTest` (16) — the complete classification matrix
  against the pure, DB-free matcher: unknown transaction, transfer
  ineligibility, matched (including two equal monetary values at scales 2
  and 4 comparing equal via `compareTo`), amount-only/currency-only/
  combined mismatch, and every internal-ledger-inconsistency case (wrong
  entry count, wrong debit account taxonomy, wrong credit account
  taxonomy, reversed debit/credit direction, unequal amount between legs,
  unequal currency between legs, a non-positive amount, both entries on
  the same side), plus a determinism test (identical inputs always
  produce an identical classification).

Integration tests (PostgreSQL 16.4 Testcontainers, never H2, never the
local Docker Compose database):
- `ReconciliationSchemaMigrationIntegrationTest` (20) — `V1`–`V6` all
  apply from an empty schema; `V1`–`V5` unchanged; both reconciliation
  tables' columns, primary keys, foreign keys (to `settlement_import`,
  `reconciliation_run`, and `settlement_record`), the
  `(settlement_import_id, algorithm_version)` and
  `(run_id, settlement_record_id)` unique constraints (including that the
  former is genuinely composite — a second row for the same import under
  a *different* algorithm version is accepted at the database level),
  every `CHECK` constraint (including the run's count-consistency
  invariant, the approved outcome-value list, and the internal
  amount/currency "populated together" pair constraint), and both
  tables' append-only `UPDATE`/`DELETE`-rejecting triggers.
- `ReconciliationIntegrationTest` (23) — HTTP-boundary tests via
  `TestRestTemplate` against all three endpoints, plus direct JDBC
  verification: a matched real deposit; a real transfer classified
  ineligible; an unknown transaction; exact amount/currency/combined
  mismatches (the currency and combined cases plant their "internal" side
  directly via JDBC, since LedgerGuard supports only USD today and a
  currency mismatch cannot otherwise be produced from valid CSV input —
  the same technique `SettlementImportIntegrationTest`'s own
  currency-conflict test uses); multiple observations in one import
  classified independently; the same underlying transaction reported by
  two different settlement imports, reconciled independently with two
  distinct run ids; a malformed internal ledger structure (a
  single-entry deposit, inserted directly, bypassing `DepositService`)
  classified inconsistent, explicitly asserting the command's own HTTP
  status stays 201 (and a repeat stays 200) — `INTERNAL_LEDGER_INCONSISTENT`
  is ordinary result data, never an HTTP/server error; a direct proof
  that corrupting `account.balance` has no effect on the result
  (reconciliation never reads it); a forced genuine PostgreSQL-level
  failure — a temporary `CHECK (1 = 0) NOT VALID` constraint added to
  `reconciliation_result` for the duration of the test, forcing the
  result insert (reached only after the owning `reconciliation_run` row
  already exists, uncommitted, in the same transaction) to fail — proving
  the whole run rolls back (no run row, no result rows), then removing
  the constraint and confirming a retry succeeds as a fresh, non-replayed
  run; an all-duplicate import producing a valid zero-result run;
  a case where `importedFileRows` exceeds `reconciliationResultCount`;
  proof that a duplicated observation is reconciled only under the
  import that first recorded it, never under a later import that merely
  re-reported it; same-import-same-algorithm-version replay (200,
  identical `runId`/`createdAt`); `GET` returning the same committed run
  a `POST` produced; a real concurrency test — four threads issuing the
  reconciliation command for the same import simultaneously produce
  exactly one committed run, and every response (including the three
  losers) references that same `runId`, proving the losers successfully
  loaded the committed winner rather than erroring — using
  `ExecutorService`/`CountDownLatch`-gated concurrent starts, never
  `Thread.sleep`; financial-and-event-table non-effects across `account`
  (row count and total balance), `ledger_transaction`, `ledger_entry`,
  `idempotency_key`, `settlement_import`, `settlement_record`,
  `outbox_event`, and `processed_event`; and request-level validation
  (nonexistent import on both the command and `GET`, an import that
  exists but was never reconciled on `GET`, a malformed import id, and
  an excessive page size).
- `ReconciliationOpenApiIntegrationTest` (6) — all three endpoints are
  documented; the command endpoint's status set is exactly
  `{201, 200, 400, 404}` and both reads' are exactly `{200, 400, 404}`;
  400/404 responses reference the shared `ApiError` schema; and no
  settlement/reconciliation endpoint exists beyond the four approved
  paths.

Every PostgreSQL-only reconciliation test suite relies on the Task 12/13
Kafka beans already being disabled under the `test` profile; none of them
start a Kafka Testcontainer (reconciliation has no Kafka dependency at
all). The forced-rollback test uses the same technique as Task 11's
`forcedOutboxInsertionFailureRollsBackTheWholeOperationAndKeySucceedsAfterCorrection`:
a temporary, real PostgreSQL `CHECK (1 = 0) NOT VALID` constraint added
to and then removed from the target table within the test itself —
`NOT VALID` so it never retroactively rejects rows other tests sharing
the same container already committed — rather than a fault-injection
mock, since no ordinary, well-formed reconciliation input can otherwise
trigger a genuine mid-transaction constraint violation.

## Reliability and Concurrency Hardening Tests (implemented, Task 16)

A test-led audit of Tasks 1–15's existing concurrency, idempotency,
rollback, outbox, Kafka, settlement, and reconciliation test suites (all
of the tests already documented above) found coverage already extensive.
The audit closed five genuine, approved gaps — **no production code was
changed**; every new/strengthened test passed against the existing
implementation on its first run.

- **Transfer idempotency race** (`IdempotencyIntegrationTest`) — until
  now, only deposit had a true concurrent-race proof
  (`simultaneousIdenticalDepositRequestsCommitExactlyOnce`,
  `simultaneousConflictingDepositRequestsWithTheSameKeyProduceExactlyOneWinner`);
  transfer had only sequential retry coverage. Two new tests,
  `simultaneousIdenticalTransferRequestsCommitExactlyOnce` and
  `simultaneousConflictingTransferRequestsWithTheSameKeyProduceExactlyOneWinner`,
  mirror the deposit versions exactly (same `ready`/`start`
  `CountDownLatch` gating, timed `invokeAll`), proving `TransferService`
  gets the same exactly-once-commit guarantee from `IdempotencyService`'s
  advisory-lock mechanism under a genuine simultaneous race, not just
  sequential replay.
- **Mixed concurrent workload and global consistency audit**
  (`MixedWorkloadConsistencyIntegrationTest`, new top-level test class) —
  a single bounded workload of 6 concurrent deposits and 8 concurrent
  transfers (including opposite-direction pairs between the same two
  accounts, and pairs across different account combinations) across four
  shared customer accounts, gated by the same `ready`/`start` latch
  pattern, followed by a consistency audit queried directly from
  PostgreSQL — never from the HTTP responses that triggered it. The audit
  does not assume every submitted transfer succeeds (each response is
  only required to be an ordinary 201 or 422, never an unhandled error);
  every expected value it checks is derived from the ledger_entry rows
  this run actually committed. It verifies: every `ledger_transaction`
  has exactly two entries with debits equal to credits (`BigDecimal`
  comparison); every account's materialized `balance` equals its own
  ledger-derived balance, computed with the real per-account-class
  formula (`ASSET`: debits − credits; `LIABILITY`: credits − debits) —
  never a hypothetical/predicted value; no negative balance; no orphaned
  `ledger_entry` (account/transaction references checked directly, not
  just trusted from the foreign key); no orphaned `outbox_event`/
  `idempotency_key` reference (checked against the real
  `outbox_event.aggregate_id`/`idempotency_key.ledger_transaction_id`
  foreign keys declared in `V3`/`V2`, confirmed by inspection rather than
  assumed) — and, since every call in this workload used its own distinct
  idempotency key, that both counts equal the committed transaction count
  exactly, not merely "non-orphaned." A spot check confirms the
  immutability triggers still reject a mutation against a row this
  workload itself just committed (the exhaustive proof of every trigger
  remains `SchemaMigrationIntegrationTest`'s job, not duplicated here).
- **Settlement import forced rollback** (`SettlementImportIntegrationTest`)
  — every other settlement rollback test is triggered by ordinary
  business logic (a within-file conflict, an invalid row); the new
  `forcedSettlementRecordInsertionFailureRollsBackTheWholeImportAndSucceedsAfterCorrection`
  forces a genuine PostgreSQL-level failure (the same temporary
  `CHECK (1 = 0) NOT VALID` technique used elsewhere) on
  `settlement_record`, so the failure lands strictly after
  `SettlementImportProcessor` has already claimed one or more rows in its
  per-row loop but before `settlement_import` itself is ever inserted
  (that insert happens last — see `docs/ARCHITECTURE.md`'s "Settlement
  Import" section). Confirms no partial `settlement_import`, no partial
  `settlement_record`, no partial deduplication state, and no change to
  `account`, `ledger_transaction`, `ledger_entry`, `idempotency_key`,
  `outbox_event`, `processed_event`, `reconciliation_run`, or
  `reconciliation_result` — then a byte-identical retry succeeds cleanly
  once the constraint is removed.
- **Outbox recovery by a newly constructed instance**
  (`OutboxPublisherIntegrationTest`) — proves restart-style recovery
  directly rather than only inferring it from `OutboxPublisher` having no
  instance fields: `pendingEventRemainsRecoverableByANewlyConstructedPublisherInstance`
  builds a fresh `OutboxPublisher` with `new` (never registered with
  Spring, sharing no in-memory state with the application's own managed
  bean, built from the same underlying repository/`KafkaTemplate`/
  properties beans a freshly started application process would itself
  inject) and calls it wrapped in an explicit `PlatformTransactionManager`
  transaction (`TransactionTemplate` — a test-only technique, since manual
  construction bypasses Spring's `@Transactional` AOP proxy, which only
  wraps container-managed beans). The pending row is claimed and
  published successfully, proving the component genuinely carries no
  required instance state.
- **Strengthened**: `DepositIntegrationTest.concurrentDepositsIntoSameWalletDoNotLoseUpdates`
  now also asserts exactly one `outbox_event` row per committed deposit
  (Task 11's atomicity guarantee, explicitly checked under real
  contention, not just assumed) and that the account's ledger-derived
  balance equals its materialized balance.

All new concurrency tests use `ExecutorService` + a `ready`/`start`
`CountDownLatch` pair (each worker signals readiness, then all release
together) + timed `invokeAll`/individual `Future.get(timeout, ...)` calls
+ `executor.shutdownNow()` in `finally` — never `Thread.sleep` as the
correctness mechanism, and no concurrency API left able to wait
indefinitely. `./mvnw verify` was run twice in immediate succession after
these additions, with identical results both times (498 tests, 0
failures), specifically to rule out timing-dependent flakiness in the new
tests.

**What Task 16 explicitly does not claim**: it does not promise
distributed exactly-once delivery across PostgreSQL and Kafka (the
existing model — atomic database transactions, transactional outbox,
at-least-once delivery, idempotent consumer processing, effectively-once
database side effects for a given event — is unchanged and is what these
tests prove held under real concurrency, not a stronger guarantee); it
does not claim every possible failure mode has been eliminated, only that
the specific scenarios above are proven correct against real PostgreSQL
(and, where broker behavior matters, real Kafka) — never mocked for a
guarantee that depends on real database or broker behavior.

## Authentication and Authorization Tests (implemented, Task 17)

Three new test classes, plus every existing HTTP-driven integration test
migrated to authenticate through the real `POST /api/v1/auth/token`
endpoint (never a security bypass, never a permissive test-only mock
user) via a shared `security.TestAuthSupport` helper and three isolated
test-only identities (`test-customer-a`, `test-customer-b`,
`test-operations`) configured in `application-test.yml`, completely
separate from the demo/dev identities.

`security.SecurityConfigurationValidatorTest` (plain unit test, no Spring
context): every startup-validation rule in isolation — empty/duplicate/
blank username, missing role, malformed BCrypt hash, non-positive/
excessive expiration or clock skew, blank/non-Base64/too-short signing
key, and that multiple simultaneous problems are all reported together.

`security.AuthenticationAndAuthorizationIntegrationTest` (27 tests,
PostgreSQL Testcontainers): valid credentials issue a token; unknown
username and wrong password both return the identical 401/message; a
token request cannot smuggle in a role field (unknown-JSON-property
rejection); missing, malformed, wrong-signature, wrong-issuer,
wrong-audience, and expired bearer tokens all return 401 (the
wrong-signature/issuer/audience/expiry cases craft a real, validly-shaped
JWT via Nimbus and sign it with the same test key the running
application actually validates against, so only the specific defect
under test is wrong); OPERATIONS is denied 403 on account creation,
deposit, and transfer; CUSTOMER is denied 403 on settlement import and
reconciliation; both 401 and 403 responses use the exact shared
`ApiError` envelope; a created account's `customer_subject` matches the
authenticated principal regardless of anything in the request body; a
CUSTOMER cannot read another customer's balance/history or deposit into
their account (404); a CUSTOMER cannot transfer from another customer's
account (404) but can transfer to one they don't own without the
response ever exposing its balance; OPERATIONS can read any customer's
balance and history. The idempotency-authorization-ordering
requirement is proven directly: a different customer reusing another
customer's exact Idempotency-Key string against the first customer's
account is rejected 404 — never the first customer's stored response —
with zero mutation to `idempotency_key`, `ledger_transaction`,
`outbox_event`, or the account's balance; and two different customers
reusing the identical key string against their *own* accounts are proven
completely independent (both succeed as genuinely new deposits, never a
409 conflict), directly demonstrating the V7 composite-uniqueness schema
change.

`OwnershipSchemaMigrationIntegrationTest` (10 tests, PostgreSQL
Testcontainers, V1–V7 applied from scratch): `V7` applies successfully;
`account.customer_subject` and `idempotency_key.principal_subject`
(`NOT NULL`) exist; `chk_account_ownership` exists and is enforced in
both directions (a `CUSTOMER` row without a subject is rejected; a
`SYSTEM` row with one is rejected; a `CUSTOMER` row with one is
accepted); `uq_idempotency_key_principal` exists and `uq_idempotency_key`
no longer does; two different principals can insert the identical raw
key string at the database level, but the same principal cannot reuse it
twice (a direct, schema-level proof of the composite uniqueness, beneath
the application-level tests above).

Every existing HTTP-driven integration test file across `account`,
`transfer`, `idempotency`, `settlement`, `reconciliation`, `outbox`,
`inbox`, and `common` was updated to authenticate — CUSTOMER for
account/deposit/transfer flows, OPERATIONS for settlement/reconciliation
— preserving every pre-existing assertion's original intent and
strength; none were weakened, skipped, or given a permissive bypass.
`common.OpenApiDocumentationIntegrationTest` gained assertions that the
`bearerAuth` security scheme is declared and applied globally except on
`POST /api/v1/auth/token` (which documents an empty per-operation
override), and that no actual secret value (BCrypt hash prefix, signing
key) ever appears in the generated document — the pre-existing blanket
"no authentication scheme at all" assertion is retired since it
described Phase 1/2, not Task 17's actual, intended behavior.
`common.StaticUiIntegrationTest` was updated to reflect a real,
security-relevant distinction Task 17 introduces: a genuinely unmapped
path *outside* `/api/v1/**` still returns 404 (the static/public
fallback), while an unmapped path *under* the now-protected
`/api/v1/**` namespace correctly returns 401 for an unauthenticated
caller before route existence is ever considered.

A genuine concurrency bug was found and fixed during this task, not
merely tested around: the new unlocked ownership pre-check in
`DepositService`/`TransferService` initially left a stale entity in the
JPA persistence context, causing the existing
`DepositIntegrationTest.concurrentDepositsIntoSameWalletDoNotLoseUpdates`,
`TransferIntegrationTest.concurrentTransfersFromOneSourceDoNotOverspend`,
and `MixedWorkloadConsistencyIntegrationTest.mixedConcurrentWorkloadPreservesAllInvariants`
to fail with real lost updates on the first full-suite run after adding
ownership checks — proving those Task 16 concurrency tests still do
their job. Fixed with an explicit `entityManager.detach(...)`; see
`docs/ARCHITECTURE.md`'s "Authentication and Authorization" section.

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

`DepositIntegrationTest` (Task 4) — see "Deposit Tests" above.

`TransferIntegrationTest` (Task 5) — see "Transfer Tests" above.

`AccountQueryIntegrationTest` (Task 6) — see "Account Balance and
Transaction History Tests" above.

`GlobalExceptionHandlingIntegrationTest` (Task 7) — see "Global Error
Handling Tests" above.

`OpenApiDocumentationIntegrationTest` (26 tests, Task 8 + Task 10) — see
"OpenAPI Documentation Tests" above; gained two Task 10 tests (the
`Idempotency-Key` header parameter contract, and the 409 response schema).

`IdempotencyIntegrationTest` (Task 10) — see "Idempotency Tests" above.

`OutboxIntegrationTest` and `OutboxEventFactoryTest` (Task 11) — see
"Outbox Tests" above.

`OutboxPublisherIntegrationTest` and `OutboxPublisherPropertiesValidationTest`
(Task 12) — see "Outbox Publisher Tests" above.

`LedgerEventConsumerIntegrationTest`, `LedgerEventValidatorTest`,
`PayloadHasherTest`, `ProcessedEventRecordTest`, and
`LedgerConsumerPropertiesValidationTest` (Task 13) — see "Kafka Consumer
Tests" above.

`SettlementSchemaMigrationIntegrationTest`, `SettlementImportIntegrationTest`,
`SettlementImportDisabledIntegrationTest`, `SettlementImportOpenApiIntegrationTest`,
`SettlementCsvParserTest`, `SettlementImportPropertiesTest`,
`RowFingerprintTest`, `Sha256Test`, and `SourceNormalizerTest` (Task 14) —
see "Settlement Import Tests" above.

Total: 417 tests (309 from Phase 1 + Tasks 10–13, plus 20 in the new
`SettlementSchemaMigrationIntegrationTest`, 28 in the new
`SettlementImportIntegrationTest`, 2 in the new
`SettlementImportDisabledIntegrationTest`, 5 in the new
`SettlementImportOpenApiIntegrationTest`, 35 in the new
`SettlementCsvParserTest`, 6 in the new `SettlementImportPropertiesTest`,
5 in the new `RowFingerprintTest`, 4 in the new `Sha256Test`, and 3 in the
new `SourceNormalizerTest` — 108 new tests, all introduced by Task 14).

`ReconciliationSchemaMigrationIntegrationTest`, `ReconciliationIntegrationTest`,
`ReconciliationOpenApiIntegrationTest`, and `ReconciliationMatcherTest`
(Task 15) — see "Settlement Reconciliation Tests" above.

`StaticUiIntegrationTest` (11 tests) — verifies the plain HTML/CSS/JS
demonstration UI under `src/main/resources/static/` is served correctly
by Spring Boot's default static-resource handling, and — just as
importantly — that it shadows no existing REST, actuator, or Swagger/
OpenAPI path; also covers a pre-existing latent defect this suite first
exposed and Task 16 leaves as-is (unrelated to Task 16's own scope): a
`NoResourceFoundException` for a genuinely unmapped path was being
misreported as 500 by the shared `Exception`-catching fallback in
`GlobalExceptionHandler` before that fix.

`MixedWorkloadConsistencyIntegrationTest`, plus the transfer
idempotency-race, settlement forced-rollback, and outbox new-instance
tests added to existing suites (Task 16) — see "Reliability and
Concurrency Hardening Tests" above.

Total: 498 tests (482 from Phase 1 + Tasks 10–15, plus 11 in the new
`StaticUiIntegrationTest` — 493 after the demonstration UI — plus 5 new
tests from Task 16's hardening: 2 in `IdempotencyIntegrationTest`
(transfer races), 1 new `MixedWorkloadConsistencyIntegrationTest`, 1 in
`SettlementImportIntegrationTest` (forced rollback), and 1 in
`OutboxPublisherIntegrationTest` (new-instance recovery) — 493 → 498).
`DepositIntegrationTest.concurrentDepositsIntoSameWalletDoNotLoseUpdates`
was strengthened with two additional assertions, not counted as a new
test.

## CI (implemented, Task 9)

`./mvnw verify` runs the full suite, including Testcontainers integration
tests, and is required to pass before any task is marked done (per
`CLAUDE.md`'s Definition of Done) — locally, and now also automatically.

`.github/workflows/ci.yml` runs on every push and pull request targeting
`master`: one job, `ubuntu-latest`, Java 21 (Temurin), that runs
`./mvnw --batch-mode --no-transfer-progress verify` — the exact same
authoritative command developers run locally, with no skipped tests and
no altered profiles. GitHub-hosted runners come with a Docker daemon
already running, so Testcontainers starts real `postgres:16.4` containers
on the runner exactly as it does on a developer's machine — no
`docker-compose.yml` service container, no shared or long-lived database.
The workflow file itself required no change for Task 12 or Task 13: the
same runner Docker daemon also starts the real `apache/kafka:3.8.0`
Testcontainers `OutboxPublisherIntegrationTest` and
`LedgerEventConsumerIntegrationTest` need, with no additional CI
configuration.
A failure at any stage (compilation, a unit test, an integration test,
Spring context startup, a Flyway migration, an immutability-trigger check,
a concurrency test, an OpenAPI schema-accuracy check — anything `verify`
already covers) fails the step, and therefore the job; nothing in the
workflow suppresses or ignores a failing exit code.

The workflow requests only `contents: read` — it never writes to the
repository, never deploys, and never publishes a package. `concurrency`
cancels a superseded run for the same branch/PR without affecting other
branches. Maven's dependency repository is cached (keyed on `pom.xml`,
via `setup-java`'s built-in `cache: maven`) — build *outputs* are never
cached, so a stale cache can only skip a redundant download, never mask a
real compilation or test failure. On failure only, Surefire/Failsafe
reports are uploaded as a short-retention (7-day) build artifact for
diagnosis; a successful run uploads nothing.

**Reproducing CI locally:** the workflow runs nothing a developer can't
run themselves — `./mvnw verify` from a checkout with Docker running is
the complete local equivalent.
