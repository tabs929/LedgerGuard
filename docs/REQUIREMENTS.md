# Phase 1 Requirements — Core Transactional Ledger

> **Status: Phase 1 complete.** All 9 tasks below are implemented. See
> `docs/TASKS.md` for the per-task breakdown and what each one covered.

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
- Duplicate requests are not expected to be idempotency-protected in Phase 1
  (idempotency is explicitly deferred to Phase 2 — see Limitations).
- JPA entities are never returned directly from API endpoints.
- PostgreSQL (via Testcontainers) is used for all persistence and
  transaction integration tests — H2 is never used as a substitute.

## Current Phase 1 Limitations (by design, not oversight)

- **Single currency:** only USD is supported. Account creation rejects any
  other currency code.
- **No FX conversion:** transfers require the source and destination
  currencies to match exactly.
- **No idempotency:** idempotency keys and duplicate-request protection are
  explicitly a Phase 2 concern per `CLAUDE.md`. Phase 1 relies on database
  transactionality (all-or-nothing commits) for correctness, not on
  deduplication of retried requests.
- **No authentication or authorization:** Spring Security and JWT are
  introduced in Phase 3. All Phase 1 endpoints are unauthenticated.
- **No Kafka / event processing:** introduced in Phase 2.
- **No withdrawals:** only deposits (money entering via the internal
  `EXTERNAL_FUNDING` account) and transfers between customer accounts exist
  in Phase 1. A withdrawal operation is designed for structurally (see
  `docs/DATA_MODEL.md`) but not implemented.
- **No reconciliation or settlement import:** introduced in Phase 2.

## Non-Goals

Per `CLAUDE.md`: no microservices, no frontend, no cloud deployment. This is
a modular monolith.
