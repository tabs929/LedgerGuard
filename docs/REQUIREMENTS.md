# Phase 1 Requirements — Core Transactional Ledger

> **Status: planning document.** This describes the approved scope for all
> of Phase 1. As of Task 1 (project foundation), none of the financial
> functionality described below is implemented. See `docs/TASKS.md` for
> per-task progress.

## Scope

Phase 1 builds the core transactional ledger for LedgerGuard:

1. Spring Boot 4.0.7 + Java 21 project via Maven Wrapper — **done (Task 1)**
2. PostgreSQL through Docker Compose — **done (Task 1)**
3. Flyway database migrations — **not implemented**
4. Account creation — **not implemented**
5. Deposits — **not implemented**
6. Transfers between accounts — **not implemented**
7. Double-entry ledger transactions — **not implemented**
8. Balance and transaction-history APIs — **not implemented**
9. Request validation — **not implemented**
10. Global API error handling — **not implemented**
11. OpenAPI/Swagger — **not implemented**
12. JUnit and PostgreSQL Testcontainers tests — partially in place (a
    connectivity smoke test exists; no persistence/business-logic tests yet)
13. GitHub Actions CI — **not implemented**

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
