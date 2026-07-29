# Phase 1: Core Transactional Ledger — Tasks

Work one task at a time. Mark a task done only when its tests pass and
`./mvnw verify` passes for the whole project.

- [ ] 1. Project foundation — Spring Boot 4.0.7 + Java 21 project via Maven
      Wrapper, package structure (`account`, `ledger`, `transfer`, `common`
      only), `application.yml` profiles (default/test), Docker Compose for
      PostgreSQL, `.env.example`, health check endpoint. No business logic yet.
- [ ] 2. Flyway baseline schema — migrations for `account`, `ledger_transaction`,
      `ledger_entry` tables; `account_category`/`account_class`/
      `account_purpose` columns with CHECK constraints plus the cross-column
      taxonomy-combination constraint; the partial unique index on
      `(account_purpose, currency) WHERE account_category = 'SYSTEM'`; the
      ledger-immutability triggers on both `ledger_entry` and
      `ledger_transaction`; the zero-balance seeded USD funding account.
      `docs/DATA_MODEL.md` and `docs/ARCHITECTURE.md` created here.
- [ ] 3. Account creation — `POST /api/v1/accounts`, `GET /api/v1/accounts/{id}`,
      request/response DTOs, validation (currency must be USD), service +
      repository with separate public-lookup vs. internal-lookup methods so
      SYSTEM accounts are 404 not-found through every public path by
      construction, unit + Testcontainers integration tests.
- [ ] 4. Deposits — `POST /api/v1/accounts/{id}/deposits`, balanced DEBIT
      `EXTERNAL_FUNDING` / CREDIT customer-account transaction; locks both
      the customer account and `EXTERNAL_FUNDING` rows in ascending id order
      before updating; treats a SYSTEM account id as 404 not-found; tests for
      success, validation, rollback, materialized-balance-matches-ledger
      correctness, and concurrent deposits racing on the shared
      `EXTERNAL_FUNDING` row.
- [ ] 5. Transfers — `POST /api/v1/transfers`, double-entry ledger transaction
      between two CUSTOMER/LIABILITY accounts, currency-match check,
      rejection of self-transfers, SYSTEM account ids treated as 404
      not-found, deterministic two-row locking (ascending id order),
      overdraft prevention, full rollback on failure, concurrency tests
      including simultaneous A→B and B→A transfers.
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
