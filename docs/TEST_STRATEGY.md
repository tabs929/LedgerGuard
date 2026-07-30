# Test Strategy

> **Status: schema-level (Task 2), account-creation (Task 3), deposit
> (Task 4), transfer (Task 5), account balance/transaction-history
> (Task 6), and global error handling (Task 7) tests are all implemented —
> no Phase 1 test-writing tasks remain planned.** The connectivity smoke
> test (Task 1), schema-verification tests (Task 2), account creation's
> tests (Task 3), deposit's ledger-balance/rollback/concurrency tests
> (Task 4), transfer's
> ledger-balance/conservation/insufficient-funds/rollback/
> deadlock-avoidance tests (Task 5), the balance/history read tests
> (Task 6), and the global error-envelope/validation/leakage tests
> (Task 7) all exist (see "Currently Implemented" below). Only `GET
> /api/v1/accounts/{id}` remains untested, since that plain-lookup
> endpoint was never assigned to any task and so still doesn't exist.

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

## CI

`./mvnw verify` runs the full suite, including Testcontainers integration
tests, and is required to pass before any task is marked done (per
`CLAUDE.md`'s Definition of Done). GitHub Actions wiring is planned for
Task 9 and does not exist yet.
