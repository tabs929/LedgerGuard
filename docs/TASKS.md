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
- [ ] 6. Balance & transaction history APIs — `GET /api/v1/accounts/{id}/balance`,
      `GET /api/v1/accounts/{id}/transactions` (paginated ledger history),
      DTOs that never expose JPA entities.
- [ ] 7. Global error handling & validation polish — `@ControllerAdvice`,
      consistent error response shape, request validation annotations across
      all endpoints, mapping of domain exceptions to HTTP status codes.
      `docs/API_SPEC.md` finalized here.
- [ ] 8. OpenAPI/Swagger — springdoc integration, annotations/descriptions on
      all Phase 1 endpoints.
- [ ] 9. GitHub Actions CI — workflow running `./mvnw verify` (including
      Testcontainers integration tests) on push/PR. `docs/TEST_STRATEGY.md`
      and `docs/REQUIREMENTS.md` finalized here.

Full design decisions, schema, transaction model, and API contracts are in
the approved plan (see project history / plan file) and will be captured in
`docs/ARCHITECTURE.md` and `docs/DATA_MODEL.md` as part of Task 2.
