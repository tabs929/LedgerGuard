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
- [ ] 13. Kafka consumption and duplicate-event protection
- [ ] 14. Settlement CSV import
- [ ] 15. Reconciliation
- [ ] 16. Phase 2 reliability, failure, and concurrency hardening
