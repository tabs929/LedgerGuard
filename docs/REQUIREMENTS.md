# Phase 1 Requirements — Core Transactional Ledger

> **Status: Phase 1 complete.** All 9 tasks below are implemented. Phase 2
> has begun — Task 10 (idempotency for deposits and transfers) and Task 11
> (transactional outbox) are also implemented. See `docs/TASKS.md` for the
> per-task breakdown and what each one covered.

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
  below); no event is published or delivered anywhere yet.
- JPA entities are never returned directly from API endpoints.
- PostgreSQL (via Testcontainers) is used for all persistence and
  transaction integration tests — H2 is never used as a substitute.

## Current Phase 1 Limitations (by design, not oversight)

- **Single currency:** only USD is supported. Account creation rejects any
  other currency code.
- **No FX conversion:** transfers require the source and destination
  currencies to match exactly.
- **No authentication or authorization:** Spring Security and JWT are
  introduced in Phase 3. All Phase 1/2 endpoints are unauthenticated.
- **No Kafka / event processing:** the outbox table exists and is written
  atomically (Task 11), but nothing reads it — no publisher, no consumer,
  no Kafka dependency of any kind. `outbox_event.published_at` exists and
  is reserved for whichever future task adds the publisher.
- **No withdrawals:** only deposits (money entering via the internal
  `EXTERNAL_FUNDING` account) and transfers between customer accounts exist
  in Phase 1/2. A withdrawal operation is designed for structurally (see
  `docs/DATA_MODEL.md`) but not implemented.
- **No reconciliation or settlement import:** still to come later in
  Phase 2.

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
- Kafka publishing/consumption, settlement CSV import, and reconciliation
  are the remaining Phase 2 scope per `CLAUDE.md`; idempotency (Task 10)
  and the transactional outbox (Task 11) are done so far.

## Non-Goals

Per `CLAUDE.md`: no microservices, no frontend, no cloud deployment. This is
a modular monolith.
