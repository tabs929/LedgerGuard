# LedgerGuard

LedgerGuard is a portfolio-quality transaction ledger and reconciliation
platform built to demonstrate backend software engineering.

## Technology Stack

- Java 21
- Spring Boot 3.5.x
- Maven with Maven Wrapper
- PostgreSQL
- Flyway
- Spring Data JPA
- Spring Security
- Apache Kafka
- Docker Compose
- JUnit 5
- Mockito
- Testcontainers
- OpenAPI/Swagger
- GitHub Actions
- Prometheus and Grafana
- k6

## Architecture

- Build a modular monolith.
- Organize packages by business capability.
- Do not introduce microservices.
- PostgreSQL is the source of truth.
- Keep controllers thin.
- Business logic belongs in application services.
- Persistence logic belongs in repositories.
- Do not return JPA entities from API endpoints.
- Use request and response DTOs.
- Do not add dependencies without explaining why they are required.
- Do not implement features outside the current task or phase.
- Do not add a frontend or cloud deployment.

## Package Structure

Use business-oriented packages similar to:

- account
- ledger
- transfer
- idempotency
- outbox
- settlement
- reconciliation
- security
- audit
- common

Each package may contain its own controller, service, model, repository and DTO
types when required.

## Financial Correctness

- Represent money using BigDecimal.
- Never use float or double for money.
- Every monetary value must have an explicit currency.
- Every completed transaction must contain balanced debit and credit entries.
- Ledger entries are immutable.
- Financial writes must execute inside database transactions.
- A failed transaction must roll back completely.
- Never update a balance without corresponding ledger entries.
- Prevent accounts from spending more than their available balance.
- Duplicate requests must not create duplicate financial transactions.
- Database constraints must enforce important invariants where possible.

## Database

- Manage all schema changes through Flyway.
- Do not use Hibernate automatic schema creation in production-style profiles.
- Use PostgreSQL in local development and integration tests.
- Do not use H2 as a substitute for PostgreSQL integration tests.
- Use explicit constraints, indexes and relationships.
- Store timestamps in UTC.
- Do not silently delete financial or audit records.

## API Design

- Version endpoints under `/api/v1`.
- Validate all request bodies.
- Return consistent error responses.
- Use appropriate HTTP status codes.
- Document APIs with OpenAPI.
- Do not reveal internal exception details to clients.
- State-changing APIs must consider idempotency.

## Testing

- Every feature requires appropriate tests.
- Use JUnit 5 for testing.
- Use Mockito only for isolated unit tests.
- Use PostgreSQL Testcontainers for persistence and transaction tests.
- Use Kafka Testcontainers when Kafka is introduced.
- Do not mock behavior that must be verified against PostgreSQL or Kafka.
- Test success, validation, rollback and failure scenarios.
- Test duplicate requests and concurrency where relevant.
- Run the complete test suite before declaring a task complete.
- Never disable or delete tests merely to make the build pass.

## Security

- Never commit credentials, passwords, tokens or populated environment files.
- Provide `.env.example` when environment variables are needed.
- Hash passwords using a secure password encoder.
- JWT secrets must come from environment variables.
- Do not log passwords, tokens or sensitive financial information.
- Authentication and authorization are introduced only in Phase 3.

## Event Processing

- Kafka is introduced only in Phase 2.
- Use a transactional outbox instead of directly dual-writing to PostgreSQL and Kafka.
- Kafka consumers must tolerate duplicate messages.
- Event-processing failures must not silently lose data.
- Event schemas must be explicit and documented.

## Workflow

- Read this file and relevant documentation before every task.
- Work on only one task from `docs/TASKS.md` at a time.
- Explain the proposed implementation before editing.
- State assumptions instead of silently inventing requirements.
- Do not modify unrelated files.
- Keep changes small and reviewable.
- Run relevant tests after every implementation.
- Run the complete test suite at the end of every task.
- Summarize changed files and commands executed.
- Do not commit changes until the user reviews them.
- Do not invent benchmarks, performance improvements or project metrics.

## Project Phases

### Phase 1: Core Transactional Ledger

- Project foundation
- PostgreSQL and Flyway
- Accounts
- Deposits
- Transfers
- Double-entry ledger
- Balance and transaction history
- Validation and error handling
- OpenAPI
- Unit and integration tests
- GitHub Actions

Do not add Kafka, Redis, authentication or cloud infrastructure in Phase 1.

### Phase 2: Reliability and Event Processing

- Idempotency
- Concurrent-transfer protection
- Transactional outbox
- Kafka publishing and consumption
- Duplicate-event protection
- Settlement CSV import
- Reconciliation
- Failure and concurrency tests

### Phase 3: Security and Portfolio Quality

- Spring Security
- JWT authentication
- Roles and authorization
- Audit logging
- Actuator and application metrics
- Prometheus and Grafana
- k6 load tests
- Final Docker Compose setup
- README, diagrams and demonstration documentation

## Definition of Done

A task is complete only when:

- Acceptance criteria are satisfied.
- Relevant tests exist and pass.
- The complete test suite passes.
- Database migrations are valid.
- Documentation is updated.
- No secrets are committed.
- Changed files and design decisions are summarized.
