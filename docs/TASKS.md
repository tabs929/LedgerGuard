# Phase 1: Core Transactional Ledger — Tasks

Work one task at a time. Mark a task done only when its tests pass and
`./mvnw verify` passes for the whole project.

- [x] 1. Project foundation — Spring Boot 4.0.7 + Java 21 project via Maven
      Wrapper, package structure (`account`, `ledger`, `transfer`, `common`
      only), `application.yml` profiles (default/test), Docker Compose for
      PostgreSQL, `.env.example`, health check endpoint. No business logic yet.
- [x] 2. Flyway baseline schema — migrations for `account`, `ledger_transaction`,
      `ledger_entry` tables; `account_category`/`account_class`/
      `account_purpose` columns with CHECK constraints plus the cross-column
      taxonomy-combination constraint; the partial unique index on
      `(account_purpose, currency) WHERE account_category = 'SYSTEM'`; the
      ledger-immutability triggers on both `ledger_entry` and
      `ledger_transaction`; the zero-balance seeded USD funding account.
      `docs/DATA_MODEL.md` and `docs/ARCHITECTURE.md` updated here.
      Implemented as `V1__init_account_ledger_schema.sql`, verified by
      `SchemaMigrationIntegrationTest` (16 tests, PostgreSQL Testcontainers).
      Schema only — no JPA entities, repositories, services, or controllers.
- [x] 3. Account creation — `POST /api/v1/accounts` implemented: request/
      response DTOs, Jakarta validation, `AccountService` (fixed
      CUSTOMER/LIABILITY/CUSTOMER_WALLET taxonomy, zero opening balance,
      USD-only currency with lowercase normalization), `AccountRepository`,
      minimal endpoint-local error handling (400 validation, 422 unsupported
      currency). Verified by `AccountCreationIntegrationTest` (7 tests,
      PostgreSQL Testcontainers, HTTP boundary + persisted DB state).
      **Scope note:** `GET /api/v1/accounts/{id}` — originally bundled into
      this task line above — was descoped to a later task per explicit
      instruction; the public-lookup vs. internal-lookup repository split
      it depends on (see `docs/ARCHITECTURE.md`) is not yet needed since no
      endpoint looks accounts up by id yet. No deposits, transfers, ledger
      entries, balance, or history were implemented.
- [x] 4. Deposits — `POST /api/v1/accounts/{id}/deposits` implemented: one
      balanced DEBIT `EXTERNAL_FUNDING` / CREDIT customer-wallet double-entry
      transaction per deposit, `DepositService` (`ledger` entities
      `LedgerTransaction`/`LedgerEntry`, `AccountRepository` locks both the
      customer account and the internally-resolved `EXTERNAL_FUNDING` row in
      ascending id order in one query — deterministic lock ordering, no
      funding-account id ever accepted from the client), materialized
      balances updated in the same transaction as the ledger writes, SYSTEM
      account ids treated as 404 not-found, minimal endpoint-local error
      handling (400 validation, 404 not found, 422 currency mismatch).
      Verified by `DepositIntegrationTest` (18 tests, PostgreSQL
      Testcontainers): balanced double-entry correctness, balance
      accumulation, validation/rejection cases, a genuine
      database-level-failure rollback test (NUMERIC(19,4) overflow),
      immutability-trigger checks, and a real concurrent-deposit test (20
      parallel HTTP requests against PostgreSQL row locking) proving no lost
      updates. No transfers, balance/history endpoints, or account lookup by
      id were implemented.
- [x] 5. Transfers — `POST /api/v1/transfers` implemented: one balanced
      DEBIT source / CREDIT destination double-entry transaction between two
      CUSTOMER/LIABILITY/CUSTOMER_WALLET accounts, `TransferService` (new
      `transfer` package, reusing `Account`/`LedgerTransaction`/`LedgerEntry`
      from Tasks 3–4), `AccountRepository.findByIdsForUpdate` locks both
      accounts in ascending id order in one query regardless of transfer
      direction, insufficient-funds rejection before any write, SYSTEM
      account ids and self-transfers rejected, full rollback on failure.
      Shared minimal error handling extracted to
      `common.AccountAndTransferExceptionHandler` (used by both
      `AccountController` and `TransferController`; behavior-preserving
      refactor, all Task 3/4 tests still pass unchanged). Verified by
      `TransferIntegrationTest` (28 tests, PostgreSQL Testcontainers):
      balanced double-entry correctness, conservation of combined balance,
      insufficient-funds and full-balance-transfer edge cases, a genuine
      database-level-failure rollback test, immutability-trigger checks,
      concurrent same-source transfers that don't overspend, and concurrent
      opposite-direction (A→B / B→A) transfers that complete without
      deadlock. No balance/history endpoints were implemented.
- [x] 6. Balance & transaction history APIs — `GET /api/v1/accounts/{id}/balance`
      and `GET /api/v1/accounts/{id}/transactions` implemented:
      `AccountQueryService` (read-only, `@Transactional(readOnly = true)`,
      no locking), `AccountBalanceResponse`/`TransactionHistoryItem`/
      `PagedResponse<T>` DTOs, `LedgerEntryRepository.findByAccountIdOrderByCreatedAtDescIdDesc`
      (one query for a page of entries + Spring Data's derived count query
      — no N+1, no full-history loading). Ordering and the custom
      pagination envelope (`{content, page, size, totalElements,
      totalPages}`, `page`/`size` defaults and bounds) were ambiguous in
      the original `docs/API_SPEC.md` stub and were confirmed with the
      user before implementation — see that file's Task 6 sections for the
      now-approved contract. SYSTEM accounts and incompatible taxonomies
      are hidden as 404, same rule as deposits/transfers. Verified by
      `AccountQueryIntegrationTest` (31 tests, PostgreSQL Testcontainers).
      **Scope note:** plain `GET /api/v1/accounts/{id}` was not
      implemented — this task's line above never included it, so it
      remains deferred, consistent with the Task 3 scope note.
- [x] 7. Global error handling & validation polish — one centralized
      `common.GlobalExceptionHandler` (`@RestControllerAdvice`) covers every
      controller (`AccountController`, `TransferController`); replaces the
      Task 5–6 `AccountAndTransferExceptionHandler`, preserving every
      status code it already produced (behavior-preserving — all Task 3–6
      tests pass unchanged). Produces the exact documented `ApiError`
      envelope (`timestamp`/`status`/`error`/`message`/`path`) for every
      handled failure: domain exceptions (404/422, reused from Tasks 3–5),
      `@Valid @RequestBody` failures and unknown-JSON-property/malformed-
      JSON rejections (400), `@Min`/`@Max` pagination violations (400), a
      malformed path UUID (400), and a safe generic fallback for anything
      unexpected including persistence-layer failures (500, logged
      server-side only). `docs/API_SPEC.md`'s "Error Response Shape"
      section finalized here. Verified by
      `GlobalExceptionHandlingIntegrationTest` (37 tests, PostgreSQL
      Testcontainers): envelope shape/consistency across every endpoint,
      request-validation and domain-error mappings, internal-detail-leakage
      checks, and atomicity/success regression.
- [x] 8. OpenAPI/Swagger — `springdoc-openapi-starter-webmvc-ui:3.0.2` added
      (the release vetted for Spring Boot 4.0.7 by start.spring.io).
      `common.OpenApiConfig` supplies API title/description/version (no
      license/contact/server — none documented anywhere, so none invented).
      `@Operation`/`@ApiResponses`/`@Parameter`/`@Schema` annotations added
      to `AccountController` and `TransferController`, matching
      docs/API_SPEC.md exactly for all 5 implemented endpoints; no new
      endpoint, path, or business behavior. `/v3/api-docs` (OpenAPI 3.1
      JSON) and `/swagger-ui/index.html` (+ the `/swagger-ui.html`
      redirect) confirmed reachable and correct. No security scheme
      declared (Phase 1 has no authentication). Verified by
      `OpenApiDocumentationIntegrationTest` (24 tests, PostgreSQL
      Testcontainers): document availability/metadata, endpoint coverage
      (including confirming plain `GET /api/v1/accounts/{id}` and any
      Task 9+ endpoint are absent), schema accuracy (required fields, UUID
      formats, decimal-string monetary fields, enum values, no protected
      fields, no JPA entities, exact `ApiError` shape), response-status
      accuracy, the custom pagination envelope (not Spring Data's `Page`),
      no internal-detail leakage, no security scheme, and a full
      create→deposit→balance→history regression alongside immutability
      re-verification.
- [x] 9. GitHub Actions CI — `.github/workflows/ci.yml` added: one job,
      `ubuntu-latest`, Java 21 (Temurin, via `actions/setup-java`), runs
      `./mvnw --batch-mode --no-transfer-progress verify` (the full
      lifecycle — unit tests + PostgreSQL 16.4 Testcontainers integration
      tests, no skipped tests, no altered profiles) on push and pull
      request to `master`. `permissions: contents: read` only;
      `concurrency` cancels a superseded in-progress run for the same
      ref; Maven dependency caching via `setup-java`'s built-in `cache:
      maven`; Surefire/Failsafe reports uploaded as a build artifact only
      `if: failure()`, 7-day retention. Docker is preinstalled on the
      GitHub-hosted runner — Testcontainers uses it directly, no
      `docker-compose.yml` service container, no shared database. Workflow
      validated locally with `actionlint` (0 errors/warnings) and PyYAML
      (parses as valid YAML) before this review; no GitHub-hosted run has
      occurred yet since nothing has been pushed. `docs/TEST_STRATEGY.md`
      and `docs/REQUIREMENTS.md` finalized here.

Full design decisions, schema, transaction model, and API contracts are in
the approved plan (see project history / plan file) and will be captured in
`docs/ARCHITECTURE.md` and `docs/DATA_MODEL.md` as part of Task 2.

# Phase 2: Reliability and Event Processing — Tasks

- [x] 10. Idempotency for deposits and transfers — `POST
      /api/v1/accounts/{id}/deposits` and `POST /api/v1/transfers` both
      require an `Idempotency-Key` header (`^[A-Za-z0-9._:-]{1,128}$`,
      validated at the controller, 400 if missing/invalid). New
      `idempotency` package: `IdempotencyKeyRecord`/`IdempotencyKeyRepository`
      (new `idempotency_key` table, `V2__add_idempotency_key.sql` — `V1`
      unmodified), `IdempotencyOperationType`, `IdempotencyCommand` (the
      canonical, normalized deposit/transfer command used for exact
      conflict/replay comparison — never a hash-only match), `IdempotencyService`
      (claim/replay/conflict orchestration via a PostgreSQL
      transaction-scoped advisory lock, `pg_advisory_xact_lock`, keyed on a
      SHA-256 hash of the raw key — not an in-memory lock, correct across
      multiple app instances), and `IdempotencyConflictException` (409, new
      `GlobalExceptionHandler` mapping alongside a `MissingRequestHeaderException`
      400 mapping). `DepositService.deposit`/`TransferService.transfer` both
      gained an `idempotencyKey` parameter and now route their existing,
      unchanged financial-write logic through `IdempotencyService.execute`;
      the financial write and the `idempotency_key` row commit or roll back
      together in the same `@Transactional` method (no `REQUIRES_NEW`) — a
      failed attempt never consumes the key. Existing deterministic
      account-row locking (Tasks 4–5) is unweakened; the advisory lock is
      always acquired before it, so no new deadlock risk. All Phase 1
      response shapes, status codes, and behavior are unchanged — every
      existing test was updated only to supply a unique `Idempotency-Key`
      per independent deposit/transfer call. OpenAPI annotations document
      the header (required, string, 1–128 chars, the approved pattern) and
      the 409 response on both endpoints only. Verified by
      `IdempotencyIntegrationTest` (21 tests, PostgreSQL Testcontainers):
      header validation, sequential replay (including numerically-equivalent
      formatting), same- and cross-operation conflict, rollback/key-not-
      consumed-on-failure, and bounded-timeout concurrency tests (simultaneous
      identical requests commit exactly once; simultaneous conflicting
      requests produce exactly one winner). `OpenApiDocumentationIntegrationTest`
      gained 2 tests for the header contract and the 409 schema. No Kafka,
      outbox, settlement, reconciliation, or authentication were introduced
      — those remain for later Phase 2/3 tasks.
- [x] 11. Transactional outbox — one `outbox_event` row is written for
      every newly committed deposit or transfer, in the same PostgreSQL
      transaction as the `ledger_transaction`, its two `ledger_entry`
      rows, the balance updates, and the Task 10 `idempotency_key` row —
      all commit or roll back together. New `outbox` package:
      `OutboxAggregateType`/`OutboxEventType`, `OutboxEvent`/
      `OutboxEventRepository` (new `outbox_event` table,
      `V3__add_transactional_outbox.sql` — `V1`/`V2` unmodified),
      `DepositCompletedEvent`/`TransferCompletedEvent` (the version-1
      envelope records), and `OutboxEventFactory` (the single insertion
      point both `DepositService`/`TransferService` call, right after
      `entityManager.refresh(transaction)` picks up the ledger
      transaction's real `createdAt`, still inside the same
      `@Transactional` method — no `REQUIRES_NEW`, no after-commit hook).
      Because the insertion point sits inside the private
      `doDeposit`/`doTransfer` methods — reachable only from
      `IdempotencyService`'s "not found, perform the operation" branch —
      a Task 10 replay or conflict structurally never reaches it, so no
      duplicate event is possible; `uq_outbox_event_identity` is a
      database-level backstop for the same guarantee. Event content
      (`id`/`aggregate_type`/`aggregate_id`/`event_type`/`schema_version`/
      `payload`/`occurred_at`/`created_at`) is immutable — two triggers
      reject `DELETE` outright and reject `UPDATE` of anything except the
      one permitted `published_at` `NULL` → non-null transition. No
      Kafka, publisher, consumer, scheduler, or background worker was
      added — `published_at` is reserved for a later task. Verified by
      `OutboxIntegrationTest` (29 tests, PostgreSQL Testcontainers):
      deposit/transfer success and exact payload shape, idempotent replay
      (including numerically-equivalent formatting) leaving exactly one
      row, same- and cross-operation conflict creating no row, bounded-
      timeout concurrency (identical requests commit one row; conflicting
      requests create a row only for the winning command), rollback
      (validation/domain/nonexistent-account failures and a forced
      ledger-level database failure leave no row; a forced outbox-insertion
      failure — a real, deterministic `CHECK (1=0) NOT VALID` constraint
      added and dropped around the test — rolls back the ledger
      transaction, entries, balances, and idempotency record together,
      and the same key succeeds with exactly one event after the
      constraint is removed), constraint checks (duplicate identity,
      invalid aggregate/event type, non-positive schema version,
      non-object payload, nonexistent aggregate id, immutability, no
      delete, `published_at` transition rules), and migration checks
      (V1/V2/V3 all apply from an empty schema, V1/V2 objects still
      exist unchanged). `OutboxEventFactoryTest` (6 unit tests, Mockito)
      covers payload construction, four-decimal monetary string
      serialization, ISO-8601 `occurredAt` formatting, schema-version
      constants, and that event ids are independently random per call.
- [x] 12. Kafka infrastructure and outbox publishing — pending
      `outbox_event` rows (Task 11) are now published to Kafka topic
      `ledger.transaction-events.v1` (configurable, 3 partitions,
      replication factor 1 for local/Testcontainers use, application-managed
      via a `NewTopic` bean). Each record: key = `aggregate_id` as a
      standard UUID string, value = the exact stored `payload` JSON text
      (never reconstructed or re-serialized), both UTF-8 strings. Producer:
      `acks=all`, `enable.idempotence=true`, string key/value serializers —
      durable acknowledgement, not end-to-end exactly-once. New `outbox`
      classes: `OutboxPublisherProperties` (validated
      `ledgerguard.outbox.publisher.*` config: `enabled`, `topic`,
      `partitions`, `replication-factor`, `poll-delay-millis`,
      `batch-size`, `send-timeout-millis`), `OutboxKafkaTopicConfig` (the
      `NewTopic` bean, conditional on `enabled=true`),
      `OutboxPublisherScheduler` (a `@Scheduled` poller, also conditional
      on `enabled=true`, that selects a bounded, deterministic
      `created_at ASC, id ASC` batch of pending event ids via the existing
      V3 partial index and hands each to `OutboxPublisher` one at a time,
      catching and logging one candidate's failure so later candidates in
      the same pass are still attempted), and `OutboxPublisher` (a
      separate `@Transactional` bean — self-invocation would bypass the
      proxy — that per candidate: `SELECT ... FOR UPDATE SKIP LOCKED`
      claims the still-pending row, sends synchronously to Kafka and
      blocks for the broker acknowledgement, and only then calls
      `OutboxEvent.markPublished(Instant)` — the one narrow mutator this
      task adds to the otherwise-immutable entity — and commits; a
      send/acknowledgement failure throws, rolling back that one
      candidate's transaction alone, leaving `published_at` untouched for
      a later polling cycle to retry). `SKIP LOCKED` is what lets multiple
      publishers (in one instance or across many) safely race the same
      row with no JVM-local locking: the loser's lock attempt returns
      empty immediately rather than blocking, so only the winner ever
      calls Kafka for that attempt. No batch-wide transaction — each event
      commits independently, so one later failure can never roll back an
      earlier already-acknowledged send back into "pending" (which would
      itself manufacture an avoidable duplicate). Deposits/transfers are
      completely unchanged — they still only ever write the outbox row;
      Kafka downtime cannot fail a financial request. This provides
      **at-least-once** publication only: a crash between a successful
      Kafka acknowledgement and the `published_at` commit can cause the
      same event to be republished later — never hidden via Kafka
      transactions, `REQUIRES_NEW`, or marking published before sending.
      `outbox_event.eventId` is what a future Task 13 consumer will use to
      detect that duplicate. `V1`/`V2`/`V3` are unmodified; no `V4` was
      needed — V3's `published_at` column, its one-way-transition trigger,
      and its partial pending index were already exactly what safe
      publishing needed. Every PostgreSQL-only integration suite disables
      the publisher via `application-test.yml`
      (`ledgerguard.outbox.publisher.enabled=false`) so it never attempts
      a Kafka connection; local development gets a single-node KRaft
      broker (`apache/kafka:3.8.0`, no ZooKeeper) added to
      `docker-compose.yml`. Verified by `OutboxPublisherIntegrationTest`
      (17 tests, real PostgreSQL 16.4 **and** real Kafka Testcontainers,
      `apache/kafka:3.8.0`, no broker behavior mocked): topic/partition
      creation, deposit/transfer publication with exact key/value/payload
      matching, idempotent replay/conflict producing no extra record,
      broker-failure leaving `published_at` null with financial/idempotency
      state unchanged and the same event publishable after recovery, one
      failed candidate not blocking a later one, two simultaneous workers
      on one event producing exactly one record, multiple distinct events
      publishing concurrently without deadlock, deterministic
      candidate ordering/bounded batching, and that the V3 immutability
      triggers remain fully effective after a real publish.
      `OutboxPublisherPropertiesValidationTest` (7 unit tests) covers the
      Jakarta Bean Validation constraints on every publisher property. No
      `@KafkaListener`, consumer, or business reaction to an event was
      added — Task 13 will add Kafka consumption and duplicate-event
      protection.
- [x] 13. Kafka consumption and duplicate-event protection — a real
      Spring Kafka `@KafkaListener` (`inbox.LedgerEventConsumer`)
      consumes `ledger.transaction-events.v1` under consumer group
      `ledgerguard-transaction-event-consumer-v1`, strictly validates
      each version-1 `DEPOSIT_COMPLETED`/`TRANSFER_COMPLETED` record
      (exact field set, UUID/timestamp/currency/amount format, Kafka
      key == `transactionId`, source ≠ destination for transfers), and
      durably records successful processing in a new `processed_event`
      table (`V4__add_processed_event_deduplication.sql` — `V1`/`V2`/`V3`
      unmodified) keyed by the event's own stable `eventId`. New `inbox`
      package: `LedgerEventConsumer` (the listener, no financial logic),
      `LedgerEventProcessor` (a separate `@Transactional` bean —
      self-invocation would bypass the proxy — that validates, computes
      a SHA-256 fingerprint over the exact Kafka value string via
      `PayloadHasher`, and atomically claims the row), `ProcessedEventRepository`
      (`NamedParameterJdbcTemplate`-based `INSERT ... ON CONFLICT
      (event_id) DO NOTHING`, deliberately not JPA — a JPA
      constraint-violation would mark the whole transaction
      rollback-only, exactly the exception-driven duplicate control flow
      the contract forbids), `LedgerEventValidator`,
      `LedgerConsumerProperties` (validated
      `ledgerguard.inbox.consumer.*` config), `LedgerEventConsumerConfig`
      (a dedicated consumer/listener-container factory pair with
      `enable.auto.commit=false` and `AckMode.MANUAL_IMMEDIATE`), and
      `ValidatedLedgerEvent`/`ProcessedEventRecord`/
      `LedgerEventValidationException`/`ConflictingEventException`. A
      claim that inserts owns first processing (success); a claim that
      finds an existing row with an identical fingerprint is a no-op
      success (safe redelivery); one with a different fingerprint throws
      `ConflictingEventException` and is never acknowledged. The listener
      acknowledges a Kafka offset only after `LedgerEventProcessor`'s
      `@Transactional` method returns — i.e. only after the PostgreSQL
      commit — and on any failure calls `Acknowledgment.nack(Duration)`
      rather than silently continuing, since with manual-immediate
      acknowledgement a later record on the same partition being
      committed would otherwise silently advance the offset past an
      earlier merely-unacknowledged one (Kafka's commit is a single
      per-partition cursor, not a per-record ledger); a permanently
      invalid or conflicting record therefore keeps retrying on its
      partition until corrected (poison-message/DLT handling is out of
      scope for Task 13). This is at-least-once Kafka delivery made
      effectively-once at the PostgreSQL layer for a given `eventId` —
      never a claim of exactly-once Kafka delivery. The consumer performs
      no settlement, reconciliation, balance update, ledger write, or any
      other business mutation — recording `processed_event` is its only
      effect; `DepositService`/`TransferService` are untouched, and Tasks
      10–12 are preserved exactly. `processed_event` has no foreign key
      to `ledger_transaction`/`outbox_event` (the consumer boundary must
      not require producer-side row access) and is append-only (two
      triggers reject `UPDATE`/`DELETE`, matching `V1`/`V3`'s style).
      Verified by `LedgerEventConsumerIntegrationTest` (19 tests, real
      PostgreSQL 16.4 **and** real Kafka Testcontainers,
      `apache/kafka:3.8.0`): migration/schema checks; deposit/transfer
      consumption via the real topic and listener with exact
      key/payload-hash/source-position verification; identical-duplicate
      handling (different offsets, different partitions, and two
      concurrent `LedgerEventProcessor` calls racing the same `eventId`
      against real PostgreSQL — no JVM-local cache); conflicting-duplicate
      rejection (different amount/transactionId/eventType, exercised via
      direct `LedgerEventProcessor` calls rather than the shared topic,
      since a permanently-rejected record retries forever and would risk
      blocking an unrelated later test sharing a partition); a forced
      genuine `processed_event`-insertion failure that rolls back cleanly
      and succeeds once corrected; and a full deposit/transfer
      Task 11→12→13 end-to-end flow proving exactly one `processed_event`
      row exists and an idempotent HTTP replay creates nothing new.
      `LedgerEventValidatorTest` (28 unit tests) exhaustively covers the
      validation matrix; `PayloadHasherTest` (5), `ProcessedEventRecordTest`
      (6), and `LedgerConsumerPropertiesValidationTest` (6) cover hashing,
      duplicate/conflict comparison, and configuration validation in
      isolation. Every PostgreSQL-only suite disables both the Task 12
      publisher and the Task 13 consumer via `application-test.yml`, so
      neither ever attempts a Kafka connection.
- [x] 14. Settlement CSV import — `POST /api/v1/settlement-imports`
      (`multipart/form-data`: `source` text field + `file` CSV part)
      records immutable external settlement observations reported by a
      bank or payment processor. **Records observations only — no
      reconciliation** (that is Task 15) and no account, ledger, outbox,
      Kafka, processed-event, or idempotency mutation of any kind. New
      `settlement` package: `SettlementImportController` (multipart
      validation only), `SettlementImportService` (bounded file reading,
      exact SHA-256 file hashing, orchestration), `SettlementCsvParser`
      (strict RFC 4180 parsing via Apache Commons CSV — a new dependency,
      no hand-rolled comma-splitting), `SettlementImportProcessor` (a
      separate `@Transactional` bean, the same self-invocation-avoidance
      pattern as `OutboxPublisher`/`LedgerEventProcessor`, performing the
      whole-file atomic import), `SettlementImportRepository`/
      `SettlementRecordRepository` (`NamedParameterJdbcTemplate`-based
      atomic `INSERT ... ON CONFLICT DO NOTHING`, deliberately not JPA —
      the same reason as `ProcessedEventRepository`), validated
      `SettlementImportProperties` (`ledgerguard.settlement.import.*`:
      `enabled`, `max-file-size-bytes`, `max-row-count`,
      `max-source-length`, `max-external-reference-length`), and small
      pure utilities `RowFingerprint`/`Sha256`/`SourceNormalizer`. Exactly
      one new migration, `V5__add_settlement_import.sql` (`V1`–`V4`
      unmodified): `settlement_import` (one row per committed whole-file
      import, unique `(normalized_source, file_hash)`) and
      `settlement_record` (one row per distinct observation, unique
      `(normalized_source, external_reference)`, **no foreign key to
      `ledger_transaction`** — an unmatched reported transaction id is
      retained as future-reconciliation evidence, not rejected — and a
      `DEFERRABLE INITIALLY DEFERRED` foreign key to `settlement_import`
      so a row can reference its not-yet-inserted parent import within
      one transaction). Both tables are append-only (the same
      `BEFORE UPDATE`/`BEFORE DELETE`-rejecting trigger style as
      `V1`/`V3`/`V4`). CSV contract: exact header
      `external_reference,transaction_id,amount,currency,settled_at`;
      UTF-8 with an optional BOM; CRLF/LF; full quoting/escaped-quote/
      embedded-comma/embedded-newline support; two-decimal `BigDecimal`
      amounts (no scientific notation, no locale formatting); currency
      restricted to what LedgerGuard actually supports (USD) but never
      compared against the referenced transaction's real amount/currency;
      an ISO-8601 `settled_at` with a mandatory explicit UTC offset; and
      an unconditional whole-file rejection of any `external_reference`
      repeated within one file. File identity is
      `(normalized_source, file_hash)` — `file_hash` is a SHA-256 hex
      digest of the exact uploaded bytes, computed before BOM
      removal/decoding/parsing. Observation identity is
      `(normalized_source, external_reference)`, fingerprinted
      (`row_hash`) via a length-prefixed canonical encoding
      (`RowFingerprint`) that is collision-resistant against
      delimiter-containing CSV content. An exact-file re-upload replays
      the original committed result (200, `replayed: true`, no new rows);
      a byte-distinct file containing an already-seen identical row
      counts it as a duplicate (201, not re-inserted); a row whose
      identity already exists with *different* business content rejects
      the **entire file** (409) and rolls back every row already claimed
      earlier in that same file. All arbitration is atomic PostgreSQL
      `INSERT ... ON CONFLICT DO NOTHING` plus a follow-up comparison
      `SELECT` — never an unlocked select-then-insert, never
      `synchronized`/a static map/Caffeine/Redis/a local file, and never a
      JPA constraint-violation exception used as duplicate control flow —
      correct across concurrent requests and multiple application
      instances with zero JVM-local coordination. The submitted filename
      is never trusted for identity/parsing/authorization and never used
      to build a filesystem path — only a sanitized basename is stored as
      audit metadata (`original_filename`, nullable); no uploaded file is
      ever written to an application-controlled path. Raw CSV/field
      values are never logged or reflected into an error message.
      `DepositService`/`TransferService` are untouched; the Task 12
      publisher was not tuned. Verified by
      `SettlementSchemaMigrationIntegrationTest` (20 tests: `V1`–`V5`
      migration/schema/constraint/trigger checks),
      `SettlementImportIntegrationTest` (28 tests: valid imports, exact
      persisted-value/hash verification, unknown/real transaction UUIDs
      accepted without ledger comparison, exact-file replay,
      identical/conflicting duplicate handling, transaction/rollback
      behavior, financial-and-event-table non-effects across `account`,
      `ledger_transaction`, `ledger_entry`, `idempotency_key`,
      `outbox_event`, and `processed_event`, request-level validation,
      and three real concurrency scenarios using
      `ExecutorService`/`CountDownLatch` — never `Thread.sleep` — to prove
      genuine PostgreSQL constraint arbitration),
      `SettlementImportDisabledIntegrationTest` (2 tests: a separate
      Spring context with the feature disabled — one explicit 503,
      every other endpoint unaffected),
      `SettlementImportOpenApiIntegrationTest` (5 tests: endpoint/schema/
      status-code documentation, no settlement CRUD/reconciliation
      endpoint), and unit tests `SettlementCsvParserTest` (35),
      `SettlementImportPropertiesTest` (6), `RowFingerprintTest` (5),
      `Sha256Test` (4), and `SourceNormalizerTest` (3) — 108 new tests
      total (417 overall). Every PostgreSQL-only suite relies on the
      Task 12/13 Kafka beans already being disabled under the `test`
      profile; none of the new tests start a Kafka Testcontainer.
- [x] 15. Reconciliation — three endpoints under
      `/api/v1/settlement-imports/{importId}/reconciliation`:
      `POST` (the command), `GET` (the summary), `GET .../results`
      (paginated item-level results, ordered by the originating
      observation's `source_row_number`). **Records and reports the
      comparison only — no ledger/balance/account/settlement-evidence/
      outbox/Kafka/processed-event/idempotency mutation of any kind, and
      no automated correction.** A run reconciles the settlement
      observations FIRST recorded by one import
      (`settlement_record.first_import_id = settlement_import.id`) — not
      necessarily every row that import's file contained, since Task 14
      deliberately stores no import-to-observation many-to-many mapping;
      an all-duplicate import legitimately produces a zero-result run,
      and the response's `importedFileRows`/`newlyRecordedObservations`/
      `duplicateRows`/`reconciliationResultCount` fields make this
      unambiguous. New `reconciliation` package:
      `ReconciliationController` (path/pagination validation only),
      `ReconciliationService` (orchestration and 404 checks),
      `ReconciliationProcessor` (a separate `@Transactional` bean, the
      same self-invocation-avoidance pattern as
      `SettlementImportProcessor`/`LedgerEventProcessor`, explicitly
      `READ COMMITTED` — required, not just preferred, so a losing
      concurrent claim's follow-up read reliably observes the winner's
      just-committed row), `ReconciliationMatcher` (a pure, DB-free
      matching function, fully unit-tested in isolation), `LedgerDataLoader`
      (three bulk queries per run regardless of row count — settlement
      observations, `ledger_transaction` rows, `ledger_entry` rows —
      never one query per observation),
      `ReconciliationRunRepository`/`ReconciliationResultRepository`
      (`NamedParameterJdbcTemplate`-based atomic
      `INSERT ... ON CONFLICT DO NOTHING`, the same reason as every
      other append-only claim table in this project), and two small,
      deliberately independent read-only repositories
      (`SettlementImportSummaryRepository`/`SettlementObservationRepository`)
      that query `settlement_import`/`settlement_record` directly rather
      than depending on the `settlement` package's own package-private
      types — Task 15 depends on nothing from Task 14's internals.
      Only `DEPOSIT` is settlement-eligible (deposits are the only
      transaction type that crosses the system boundary via
      `EXTERNAL_FUNDING`); a reported `TRANSFER` is classified
      `INELIGIBLE_TRANSACTION_TYPE`, never compared against any
      amount/currency. For an eligible deposit, the complete posting
      structure is independently revalidated before its values are
      trusted — exactly two entries, one DEBIT against
      `SYSTEM`/`ASSET`/`EXTERNAL_FUNDING`, one CREDIT against
      `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`, both positive, equal
      amounts (`BigDecimal.compareTo`, never `equals` — reported values
      are `NUMERIC(19,2)`, internal values are `NUMERIC(19,4)`, different
      scales for numerically-equal amounts; never `float`/`double`), and
      equal currencies; any violation is `INTERNAL_LEDGER_INCONSISTENT`.
      `account.balance` is never read or compared anywhere in the
      algorithm. Seven mutually exclusive outcomes: `MATCHED`,
      `INTERNAL_TRANSACTION_NOT_FOUND`, `INELIGIBLE_TRANSACTION_TYPE`,
      `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`,
      `AMOUNT_AND_CURRENCY_MISMATCH`, `INTERNAL_LEDGER_INCONSISTENT` —
      every one of them except the last (a data-integrity finding about
      LedgerGuard's own data) is expected result data in a successful
      2xx response, never an HTTP-level command failure. No
      `settled_at` comparison (no internal timestamp shares its
      meaning) and no missing-external detection (Task 14 records
      nothing about expected provider coverage, settlement period, or
      completeness). Exactly one new migration,
      `V6__add_settlement_reconciliation.sql` (`V1`–`V5` unmodified):
      `reconciliation_run` (one row per committed
      `(settlement_import_id, algorithm_version)`, unique on that pair —
      not `settlement_import_id` alone, so a future approved algorithm
      version can produce a new run without colliding with version 1) and
      `reconciliation_result` (one row per reconciled observation, unique
      `(run_id, settlement_record_id)`, foreign keys to
      `reconciliation_run` and `settlement_record`, reported/internal
      amount and currency snapshotted at creation time). Both tables are
      append-only (the same `BEFORE UPDATE`/`BEFORE DELETE`-rejecting
      trigger style as `V1`/`V3`/`V4`/`V5`). A repeated command for the
      same `(import, algorithm version)` replays the existing committed
      run (200); concurrent identical commands collapse into one run via
      atomic `INSERT ... ON CONFLICT DO NOTHING` — never a JVM lock,
      static map, or cache — with the loser reliably loading the winner's
      committed row under `READ COMMITTED`. `DepositService`/
      `TransferService` are untouched; no outbox publisher tuning. Verified
      by `ReconciliationSchemaMigrationIntegrationTest` (20 tests:
      `V1`–`V6` migration/schema/constraint/trigger checks, including that
      the composite unique constraint genuinely permits a second
      algorithm version for the same import), `ReconciliationIntegrationTest`
      (23 tests: matched deposit, ineligible transfer, unknown
      transaction, exact amount/currency/combined mismatches, multiple
      observations per import, the same transaction reported by two
      different imports reconciled independently, a malformed internal
      ledger structure (explicitly asserting the command's own HTTP status
      stays 201/200 — `INTERNAL_LEDGER_INCONSISTENT` is ordinary result
      data, never an HTTP/server error), proof that `account.balance` is
      never consulted, a forced genuine PostgreSQL-level failure (a
      temporary `CHECK (1 = 0) NOT VALID` constraint on
      `reconciliation_result`, added and removed within the test) proving
      the whole run rolls back with no partial run/results and that a
      retry succeeds once the constraint is removed, an all-duplicate
      zero-result run, `importedFileRows` exceeding
      `reconciliationResultCount`, first-import-id-only duplicate
      scoping, same-import-same-version replay, `GET` returning the
      committed run, a real four-way concurrency test proving exactly one
      committed run and that every loser loads the winner, financial/
      event-table non-effects, and request-level validation),
      `ReconciliationOpenApiIntegrationTest` (6 tests: endpoint/status-code/
      `ApiError`-schema documentation, no extra settlement/reconciliation
      endpoint), and `ReconciliationMatcherTest` (16 unit tests: the
      complete classification matrix, including exact-decimal comparison
      across different scales and full internal-ledger-inconsistency
      coverage) — 65 new tests total (482 overall). Every PostgreSQL-only
      suite relies on the Task 12/13 Kafka beans already being disabled
      under the `test` profile; reconciliation has no Kafka dependency at
      all.
- [x] 16. Phase 2 reliability, failure, and concurrency hardening — a
      test-led audit of Tasks 1–15's existing concurrency, idempotency,
      rollback, outbox, Kafka, settlement, and reconciliation test
      coverage found it already extensive; five genuine, approved gaps
      were closed and **zero production code was changed** — the audit
      found no correctness defect, only test coverage gaps. New/
      strengthened tests: (1) `IdempotencyIntegrationTest` gained
      `simultaneousIdenticalTransferRequestsCommitExactlyOnce` and
      `simultaneousConflictingTransferRequestsWithTheSameKeyProduceExactlyOneWinner`
      — transfer now has the same true concurrent-race proof deposit
      already had (previously only sequential retry was tested for
      transfer). (2) New top-level `MixedWorkloadConsistencyIntegrationTest`
      — a single bounded concurrent workload mixing deposits and
      transfers (including opposite-direction pairs) across four shared
      accounts, followed by a global consistency audit queried directly
      from PostgreSQL: every `ledger_transaction` has exactly two
      balanced entries, every account's materialized balance equals its
      own ledger-derived balance (computed via the real per-account-class
      formula, never predicted from HTTP responses), no negative balance,
      no orphaned `ledger_entry`/`outbox_event`/`idempotency_key`
      reference, and outbox/idempotency-key counts match the transaction
      count exactly (Task 10/11's 1:1 guarantees holding under real
      contention). Does not assume every submitted transfer succeeds —
      each response is accepted as either 201 or 422, and the audit is
      correct either way since it only trusts committed rows.
      (3) `SettlementImportIntegrationTest` gained
      `forcedSettlementRecordInsertionFailureRollsBackTheWholeImportAndSucceedsAfterCorrection`
      — the one settlement-import rollback scenario that was previously
      untested with a genuine PostgreSQL-level failure (a temporary
      `CHECK (1 = 0) NOT VALID` constraint, the same test-only technique
      Tasks 11/13/15 already established), proving no partial
      `settlement_import`/`settlement_record` state and a clean retry
      after the constraint is removed. (4)
      `OutboxPublisherIntegrationTest` gained
      `pendingEventRemainsRecoverableByANewlyConstructedPublisherInstance`
      — a brand-new `OutboxPublisher` instance, constructed with `new`
      (never registered with Spring, sharing no in-memory state with the
      application's managed bean) and wrapped in an explicit
      `PlatformTransactionManager` transaction (manual construction
      bypasses the `@Transactional` AOP proxy), successfully claims and
      publishes a pending row — direct proof of restart-style recovery,
      not just an architectural inference from statelessness.
      (5) `DepositIntegrationTest.concurrentDepositsIntoSameWalletDoNotLoseUpdates`
      strengthened with an exact outbox-row-count assertion and a
      ledger-derived-vs-materialized-balance cross-check. All concurrency
      coordination uses `ExecutorService` + `CountDownLatch` ready/start
      gating + timed `invokeAll`/`Future.get`, never `Thread.sleep`;
      `shutdownNow()` in every new test's `finally` block. Verified by
      `./mvnw verify` run twice in succession with identical results (498
      tests both times) to rule out flakiness. See
      `docs/TEST_STRATEGY.md`'s "Reliability and Concurrency Hardening
      Tests" section for the full list, and `docs/ARCHITECTURE.md`'s
      "Reliability Hardening" section for what this task did and
      deliberately did not claim.
- [x] 17. Authentication and ownership-based authorization (Phase 3) —
      stateless HS256 JWT authentication (`POST /api/v1/auth/token`,
      `security.SecurityConfig`/`JwtIssuer`/`TokenController`),
      configuration-backed CUSTOMER/OPERATIONS demo identities (BCrypt
      hashes only, never plaintext), and two-layer authorization: a
      coarse `SecurityFilterChain` URL/role gate plus independent
      service-layer ownership checks in `AccountService`,
      `AccountQueryService`, `DepositService`, `TransferService`,
      `SettlementImportService`, and `ReconciliationService` (via the
      shared `security.AuthorizationSupport`), so bypassing a controller
      cannot bypass authorization. `Flyway V7` adds
      `account.customer_subject` (NOT NULL for CUSTOMER, NULL for
      SYSTEM, enforced by `chk_account_ownership`; legacy rows backfilled
      to a fixed `legacy-unowned-customer` sentinel) and
      `idempotency_key.principal_subject`, changing idempotency
      uniqueness from `idempotency_key` alone to the composite
      `(principal_subject, idempotency_key)` — closing a genuine
      pre-existing gap where two different authenticated principals could
      otherwise collide, replay, or conflict over the same literal key
      string. `V1`–`V6` unmodified. A CUSTOMER may transfer to an account
      they do not own but never read/deposit-into/transfer-from one;
      ownership violations return 404 (consistent with the existing
      SYSTEM-account-as-404 precedent), blanket role denials return 403,
      and authentication failures return 401 — all via the shared
      `ApiError` envelope, produced for filter-chain-level failures by a
      dedicated `JwtAuthenticationEntryPoint`/`ApiAccessDeniedHandler`
      pair (Spring Security runs before `GlobalExceptionHandler`).
      Authorization is checked before any idempotency claim/replay, so a
      different principal reusing another principal's exact key string
      can never retrieve their stored response or mutate any state.
      Genuinely unmapped top-level paths still return 404 via the
      existing MVC fallback; an unmapped path *under* `/api/v1/**`
      correctly returns 401 first (the protected namespace itself, not
      route existence, is what unauthenticated callers see). During
      implementation, an unlocked pre-idempotency ownership read was
      found to leave a stale entity in the JPA persistence context,
      silently reintroducing a lost-update race under concurrent
      deposits/transfers — caught by the existing concurrency tests and
      fixed by explicitly detaching the entity
      (`DepositService`/`TransferService`). Demo UI gained a sign-in/out
      section; the access token lives only in an in-memory JS variable,
      never `localStorage`/`sessionStorage`. `./mvnw verify` run twice in
      succession with identical results (557 tests both times, up from
      498) to rule out flakiness. See `docs/ARCHITECTURE.md`'s
      "Authentication and Authorization" section, `docs/DATA_MODEL.md`'s
      "Customer Ownership and Principal-Scoped Idempotency (V7)" section,
      and `docs/API_SPEC.md`'s "Authentication" section and access
      matrix.
