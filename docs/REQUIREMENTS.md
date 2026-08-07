# Phase 1 Requirements — Core Transactional Ledger

> **Status: Phase 1 complete.** All 9 tasks below are implemented. **Phase
> 2 is complete** — Task 10 (idempotency), Task 11 (transactional
> outbox), Task 12 (Kafka publishing of outbox events), Task 13 (Kafka
> consumption and duplicate-event protection), Task 14 (settlement
> CSV import), Task 15 (settlement reconciliation), and Task 16
> (reliability, failure, and concurrency hardening — test-only, no
> production changes) are all
> implemented. **Phase 3 has begun** — Task 17 (stateless JWT
> authentication and ownership-based authorization) is implemented; see
> "Phase 3 Progress" below. See `docs/TASKS.md` for the per-task
> breakdown and what each one covered.

## Scope

Phase 1 builds the core transactional ledger for LedgerGuard:

1. Spring Boot 4.0.7 + Java 21 project via Maven Wrapper — **done (Task 1)**
2. PostgreSQL through Docker Compose — **done (Task 1)**
3. Flyway database migrations — **done (Task 2)**
4. Account creation — **done (Task 3)**
5. Deposits — **done (Task 4)**
6. Transfers between accounts — **done (Task 5)**
7. Double-entry ledger transactions — **done (Tasks 2, 4, 5)**
8. Balance and transaction-history APIs — **done (Task 6)**
9. Request validation — **done (Tasks 3–7)**
10. Global API error handling — **done (Task 7)**
11. OpenAPI/Swagger — **done (Task 8)**
12. JUnit and PostgreSQL Testcontainers tests — **done (Tasks 1–8)**, 163
    tests across 8 test classes, all against real PostgreSQL 16.4
    Testcontainers, never H2
13. GitHub Actions CI — **done (Task 9)**, `.github/workflows/ci.yml`

## Acceptance Criteria (Phase 1, overall)

- Money is represented with `BigDecimal` and an explicit currency; float/
  double are never used for money.
- Every completed transaction has balanced debit and credit ledger entries.
- Ledger entries are immutable once written.
- Transfers execute inside a single database transaction; a failed transfer
  leaves no partial writes.
- Accounts cannot spend more than their available balance.
- Duplicate requests to `POST /api/v1/accounts/{id}/deposits` and
  `POST /api/v1/transfers` are idempotency-protected via a required
  `Idempotency-Key` header (Phase 2, Task 10 — see below). Account creation
  and both read endpoints do not require or accept this header.
- Every newly committed deposit or transfer durably records exactly one
  domain event in a PostgreSQL transactional outbox, atomically with the
  financial write and the idempotency record (Phase 2, Task 11 — see
  below).
- Every pending outbox event is eventually published to Kafka, at least
  once, by a polling publisher independent of the deposit/transfer
  request itself — Kafka downtime never fails a financial request (Phase
  2, Task 12 — see below). This is at-least-once publication, not
  exactly-once delivery.
- Every published event is durably recorded exactly once in PostgreSQL by
  a real Kafka consumer, keyed by the event's own stable `eventId` — a
  redelivered duplicate is a safe no-op, and a conflicting reuse of an
  `eventId` is rejected and never acknowledged as successful (Phase 2,
  Task 13 — see below). Kafka delivery itself remains at-least-once; the
  PostgreSQL-side effect is what is idempotent.
- JPA entities are never returned directly from API endpoints.
- PostgreSQL (via Testcontainers) is used for all persistence and
  transaction integration tests — H2 is never used as a substitute.

## Current Phase 1 Limitations (by design, not oversight)

- **Single currency:** only USD is supported. Account creation rejects any
  other currency code.
- **No FX conversion:** transfers require the source and destination
  currencies to match exactly.
- **No authentication or authorization in Phase 1/2:** all endpoints
  implemented before Task 17 were unauthenticated at the time. Task 17
  (Phase 3) adds stateless JWT authentication and ownership-based
  authorization to every one of them — see "Phase 3 Progress" below.
- **No business reaction to events:** Task 13 consumes and durably
  deduplicates events, but performs no settlement, reconciliation,
  balance update, ledger write, notification, or any other downstream
  business logic — recording `processed_event` is its only effect.
- **No withdrawals:** only deposits (money entering via the internal
  `EXTERNAL_FUNDING` account) and transfers between customer accounts exist
  in Phase 1/2. A withdrawal operation is designed for structurally (see
  `docs/DATA_MODEL.md`) but not implemented.
- **No reconciliation-driven correction:** Task 15 compares external
  settlement observations against the ledger and durably records the
  classification, but never corrects a discrepancy, never mutates the
  ledger/accounts/settlement evidence, and performs no automated action
  of any kind based on the result.
- **No exactly-once delivery:** Task 12 is at-least-once publication.
  Kafka producer idempotence (enabled) suppresses duplicate broker-retry
  sends within one producer session — it does not close the window
  between a successful broker acknowledgement and the `published_at`
  database commit. A crash in that window can cause the same event to be
  published again on a later retry. No Kafka transactions, `REQUIRES_NEW`,
  or two-phase commit are used to hide this — see
  `docs/ARCHITECTURE.md`'s "Kafka Publishing" section. Task 13's consumer
  makes the *PostgreSQL-side effect* of a redelivered duplicate a
  no-op — it does not and cannot make Kafka delivery itself exactly-once.

## Phase 2 Progress

- **Task 10 — Idempotency (implemented):** `Idempotency-Key` header
  required on deposits and transfers, PostgreSQL-native concurrency
  (transaction-scoped advisory lock — not in-memory), exact replay of the
  original response, 409 on conflicting reuse (including across deposit
  vs. transfer), and indefinite key retention (no TTL/cleanup in Phase 2).
  See `docs/API_SPEC.md`'s "Idempotency" section and
  `docs/ARCHITECTURE.md`'s "Idempotency" section for the full contract and
  mechanism.
- **Task 11 — Transactional outbox (implemented):** one `outbox_event` row
  per newly committed deposit or transfer, written in the exact same
  PostgreSQL transaction as the ledger write and the Task 10 idempotency
  record — never independently, never after commit. A Task 10 replay or
  conflict creates no event. No Kafka, publisher, consumer, or scheduler
  exists yet — this is durable persistence only. See
  `docs/ARCHITECTURE.md`'s "Transactional Outbox" section and
  `docs/DATA_MODEL.md`'s "Outbox Event Table" section.
- **Task 12 — Kafka publishing (implemented):** pending `outbox_event` rows
  are published to topic `ledger.transaction-events.v1` by a scheduled
  poller, one PostgreSQL transaction per event, `FOR UPDATE SKIP LOCKED`
  row-claiming for multi-instance safety, `published_at` set only after a
  successful broker acknowledgement. At-least-once, not exactly-once. See
  `docs/ARCHITECTURE.md`'s "Kafka Publishing" section.
- **Task 13 — Kafka consumption and duplicate-event protection
  (implemented):** a real `@KafkaListener` durably records each
  successfully validated event in a new `processed_event` table, keyed by
  `eventId`. First delivery: recorded. Identical redelivery: a safe
  no-op. Conflicting reuse of an `eventId`: rejected, never acknowledged.
  The Kafka offset is only acknowledged after the PostgreSQL transaction
  commits. No settlement, reconciliation, balance update, or ledger
  mutation is performed. See `docs/ARCHITECTURE.md`'s "Kafka Consumption"
  section and `docs/DATA_MODEL.md`'s "Processed Event Table" section.
- **Task 14 — Settlement CSV import (implemented):** `POST
  /api/v1/settlement-imports` (`multipart/form-data`) parses and strictly
  validates an external bank/processor's settlement CSV, then atomically
  claims a whole-file identity `(source, file_hash)` and a per-row
  identity `(source, external_reference)` via PostgreSQL `INSERT ...
  ON CONFLICT DO NOTHING` — never an unlocked select-then-insert, never a
  JVM lock or cache. An exact-file re-upload replays the original
  committed result (200); a byte-distinct file containing an already-seen
  identical row counts it as a duplicate; a row whose identity already
  exists with *different* content rejects the entire file (409) and rolls
  back every row from it. Records external observations only — no
  reconciliation, and no account, ledger, outbox, Kafka, processed-event,
  or idempotency mutation of any kind. See `docs/ARCHITECTURE.md`'s
  "Settlement Import" section and `docs/DATA_MODEL.md`'s "Settlement
  Import Tables" section.
- **Task 15 — Settlement reconciliation (implemented):** compares the
  settlement observations first recorded by one settlement import
  (`settlement_record.first_import_id = settlement_import.id`) against
  LedgerGuard's own ledger. Only `DEPOSIT` transactions are
  settlement-eligible; a reported `TRANSFER` is classified
  `INELIGIBLE_TRANSACTION_TYPE`. The authoritative internal amount/currency
  come from a freshly-revalidated `ledger_entry` posting structure —
  never `account.balance`. Seven mutually exclusive outcomes: `MATCHED`,
  `INTERNAL_TRANSACTION_NOT_FOUND`, `INELIGIBLE_TRANSACTION_TYPE`,
  `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `AMOUNT_AND_CURRENCY_MISMATCH`,
  `INTERNAL_LEDGER_INCONSISTENT` — every one of them (except the last, a
  data-integrity finding) is expected result data in a successful 2xx
  response, never an HTTP-level failure. A run's identity is
  `(settlement_import_id, algorithm_version)`; a repeated command for the
  same import and version (Task 15 always uses version 1) replays the
  existing committed run. Zero financial mutation: no ledger/account/
  balance/settlement-evidence/outbox/Kafka/processed-event/idempotency
  change of any kind, and no automated correction. See
  `docs/ARCHITECTURE.md`'s "Settlement Reconciliation" section and
  `docs/DATA_MODEL.md`'s "Settlement Reconciliation Tables" section.
- **Task 16 — Reliability, failure, and concurrency hardening
  (implemented):** a test-led audit of Tasks 1–15's existing concurrency,
  idempotency, rollback, outbox, Kafka, settlement, and reconciliation
  test coverage, which found it already extensive. Closed five genuine
  gaps (a true concurrent idempotency race for transfer, not just
  deposit; a mixed concurrent deposit/transfer workload across shared
  accounts followed by a global PostgreSQL-level consistency audit; a
  genuine forced-database-failure rollback test for settlement import;
  direct proof that a newly constructed, non-Spring-managed
  `OutboxPublisher` instance recovers a pending event; and a strengthened
  concurrent-deposit test). **No production code was changed** — the
  audit found no correctness defect. Does not claim distributed
  exactly-once delivery across PostgreSQL and Kafka, and does not claim
  every possible failure mode has been eliminated. See
  `docs/ARCHITECTURE.md`'s "Reliability Hardening" section and
  `docs/TEST_STRATEGY.md`'s "Reliability and Concurrency Hardening Tests"
  section.
- **Phase 2 is now fully complete** per `CLAUDE.md`: idempotency
  (Task 10), the transactional outbox (Task 11), Kafka publishing
  (Task 12), Kafka consumption/dedup (Task 13), settlement CSV import
  (Task 14), settlement reconciliation (Task 15), and reliability
  hardening (Task 16) are all done.

## Phase 3 Progress

- **Task 17 — Authentication and ownership-based authorization
  (implemented):** stateless HS256 JWT authentication via
  `POST /api/v1/auth/token`, two fixed configuration-backed identities
  (CUSTOMER, OPERATIONS — BCrypt hashes only), and ownership-based
  authorization enforced at both the `SecurityFilterChain` and the
  service layer independently. A CUSTOMER account's owner
  (`account.customer_subject`, Flyway V7) is sourced exclusively from the
  authenticated JWT subject — never from request JSON. A CUSTOMER may
  read/deposit-into/transfer-from only accounts they own (404 if not,
  consistent with the existing SYSTEM-account-as-404 precedent) but may
  transfer *to* any valid account; OPERATIONS may read any account and
  exclusively performs settlement import/reconciliation; neither role can
  do the other's write operations. Idempotency-key uniqueness is now
  scoped per authenticated principal (V7's composite
  `(principal_subject, idempotency_key)`), and ownership is always
  checked before any idempotency claim or replay, so one principal can
  never retrieve or influence another's stored response. See
  `docs/ARCHITECTURE.md`'s "Authentication and Authorization" section,
  `docs/DATA_MODEL.md`'s "Customer Ownership and Principal-Scoped
  Idempotency (V7)" section, and `docs/API_SPEC.md`'s "Authentication"
  section.

## Non-Goals

Per `CLAUDE.md`: no microservices, no frontend, no cloud deployment. This is
a modular monolith.
