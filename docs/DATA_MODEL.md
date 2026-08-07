# Data Model

> **Status: `account`/`ledger_transaction`/`ledger_entry` schema
> implemented (Task 2, Flyway V1); account creation (Task 3), deposits
> (Task 4), transfers (Task 5), account balance/transaction-history reads
> (Task 6), idempotency for deposits/transfers (Task 10, Flyway V2), the
> transactional outbox (Task 11, Flyway V3), Kafka publishing of pending
> outbox events (Task 12), durably deduplicated Kafka consumption
> (Task 13, Flyway V4), settlement CSV import (Task 14, Flyway V5), and
> settlement reconciliation (Task 15, Flyway V6) implemented.** Task 12
> added no new migration —
> `V3`'s `published_at` column, its one-way-transition trigger, and its
> pending partial index were already exactly what safe publishing needed;
> see "Outbox Event Table" below for how `published_at` is now actually
> used. Task 13 adds exactly one new migration,
> `V4__add_processed_event_deduplication.sql` — see "Processed Event
> Table" below. Task 14 adds exactly one new migration,
> `V5__add_settlement_import.sql` — see "Settlement Import Tables" below.
> Task 15 adds exactly one new migration,
> `V6__add_settlement_reconciliation.sql` — see "Settlement Reconciliation
> Tables" below. Task 17 adds exactly one new migration,
> `V7__add_customer_ownership_and_idempotency_principal.sql` — see
> "Customer Ownership and Principal-Scoped Idempotency (V7)" below.
> `V1`–`V6` are unmodified by Task 17. The `V1`
> tables, constraints, indexes, and triggers described below exist in the
> database via
> `src/main/resources/db/migration/V1__init_account_ledger_schema.sql`
> (unmodified since Task 2) and are verified by
> `SchemaMigrationIntegrationTest`. `account`, `ledger_transaction`, and
> `ledger_entry` all have matching JPA entities, written by account
> creation, deposit, and transfer processing, and also read by
> `AccountQueryService` (Task 6) — which never writes to any of them. The
> materialized `account.balance` it returns and the `ledger_entry` history
> it returns are two distinct things: the former is a cached number kept in
> lockstep with the latter by every write path; the latter is the immutable
> record those numbers are derived from. `idempotency_key`
> (`V2__add_idempotency_key.sql`, Task 10) and `outbox_event`
> (`V3__add_transactional_outbox.sql`, Task 11) are both new, separate
> tables — see "Idempotency Key Table" and "Outbox Event Table" below. `V1`
> and `V2` are unmodified by Task 11.

## Account Taxonomy

Three columns, not one, describe what an account is:

- `account_category`: `CUSTOMER` | `SYSTEM`
- `account_class` (normal-balance side): `ASSET` | `LIABILITY`
- `account_purpose`: `CUSTOMER_WALLET` | `EXTERNAL_FUNDING` (extensible to
  future `SYSTEM` purposes, e.g. a fee or suspense account, without
  redefining `account_category`)

Only two `(category, class, purpose)` combinations are valid in Phase 1,
enforced by a cross-column `CHECK` constraint:

| account_category | account_class | account_purpose  |
|-------------------|---------------|-------------------|
| CUSTOMER           | LIABILITY     | CUSTOMER_WALLET   |
| SYSTEM              | ASSET         | EXTERNAL_FUNDING  |

Individual per-column `CHECK` constraints alone don't stop invalid
*combinations* (e.g. `CUSTOMER + ASSET + EXTERNAL_FUNDING`); the
cross-column constraint closes that gap. **Adding a new account purpose in
the future requires a Flyway migration to widen this constraint** — it is
not extensible without a schema change.

`EXTERNAL_FUNDING` is seeded once per supported currency (USD only in
Phase 1), starts at balance `0` (no fake seed money), and is enforced unique
per currency via a partial unique index on `(account_purpose, currency)
WHERE account_category = 'SYSTEM'`.

## Debit/Credit Balance Formulas

Balance direction depends on account class:

- **ASSET** (`EXTERNAL_FUNDING`): `balance = total debits − total credits`
- **LIABILITY** (customer wallets): `balance = total credits − total debits`

## Posting Rules

- **Deposit** (implemented, Task 4): DEBIT `EXTERNAL_FUNDING` (asset) /
  CREDIT customer account (liability). Both entries carry the same amount
  and currency and reference the same `ledger_transaction` row
  (`transaction_type = DEPOSIT`, `status = COMPLETED`). Both accounts'
  balances increase by the deposit amount — an asset debit and a liability
  credit both increase their respective balance, per the formulas above,
  which is exactly what keeps the books balanced: money entering the
  system from outside increases what the platform holds (the funding
  asset) by the same amount it increases what the platform owes the
  customer (the wallet liability).
- **Transfer** (implemented, Task 5): DEBIT source (liability) / CREDIT
  destination (liability), both `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`
  accounts. Both entries carry the same amount and currency and reference
  the same `ledger_transaction` row (`transaction_type = TRANSFER`,
  `status = COMPLETED`). The source's balance decreases and the
  destination's increases by the transfer amount — both are liability
  accounts, so a debit decreasing one and a credit increasing the other is
  exactly what keeps the *combined* balance of the two accounts unchanged:
  unlike a deposit, no money enters or leaves the ledger, it only moves
  between two liabilities the platform already owed. `EXTERNAL_FUNDING` is
  never involved.
- **Withdrawal** (designed for, not implemented in Phase 1): DEBIT customer
  account (liability) / CREDIT `EXTERNAL_FUNDING` (asset).

## Ledger Entry Model

`ledger_entry` has no schema-level restriction to exactly two rows per
`transaction_id`. The domain rule is: for a given transaction, entries of
the same currency must have total debits equal to total credits. Phase 1's
two operations (deposit, transfer) each always produce exactly two entries,
but the model supports two-or-more so a future transaction type (e.g. a
multi-leg settlement) can post more entries without a redesign.

**PostgreSQL does not automatically enforce this balancing invariant** — no
deferred constraint trigger exists for it in Phase 1. It is enforced by the
domain service (both entries of a transaction are written together inside
one `@Transactional` method) and verified by integration tests (the
trial-balance invariant — see `docs/TEST_STRATEGY.md`).

## Ledger Immutability

Both `ledger_entry` and `ledger_transaction` reject `UPDATE`/`DELETE` via a
`BEFORE UPDATE OR DELETE` PostgreSQL trigger, since both are part of the
authoritative financial record.

## Implemented Schema (Flyway V1, Task 2)

```sql
CREATE TABLE account (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_category    VARCHAR(20) NOT NULL,
    account_class       VARCHAR(20) NOT NULL,
    account_purpose     VARCHAR(30) NOT NULL,
    owner_name          VARCHAR(255) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    balance             NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_category CHECK (account_category IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT chk_account_class CHECK (account_class IN ('ASSET', 'LIABILITY')),
    CONSTRAINT chk_account_purpose CHECK (account_purpose IN ('CUSTOMER_WALLET', 'EXTERNAL_FUNDING')),
    CONSTRAINT chk_account_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_account_balance_nonneg CHECK (balance >= 0),
    CONSTRAINT chk_account_taxonomy_combination CHECK (
        (account_category = 'CUSTOMER' AND account_class = 'LIABILITY' AND account_purpose = 'CUSTOMER_WALLET')
        OR
        (account_category = 'SYSTEM' AND account_class = 'ASSET' AND account_purpose = 'EXTERNAL_FUNDING')
    )
);

CREATE UNIQUE INDEX uq_system_account_purpose_currency
    ON account (account_purpose, currency)
    WHERE account_category = 'SYSTEM';

CREATE TABLE ledger_transaction (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type  VARCHAR(30) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('DEPOSIT', 'TRANSFER')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('COMPLETED'))
);

CREATE TABLE ledger_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL REFERENCES ledger_transaction(id),
    account_id          UUID NOT NULL REFERENCES account(id),
    entry_type          VARCHAR(10) NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_ledger_entry_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);
```

Plus indexes on `ledger_entry(account_id, created_at)` and
`ledger_entry(transaction_id)`, and immutability triggers on both ledger
tables (see `docs/ARCHITECTURE.md`). The single seeded row —
`SYSTEM/ASSET/EXTERNAL_FUNDING`, currency `USD`, balance `0` — is inserted
by the same migration; no customer accounts or other example data are
seeded.

**What the schema enforces on its own** (verified in
`SchemaMigrationIntegrationTest`, no application code involved):
account-taxonomy combinations, currency format, non-negative balances,
one `EXTERNAL_FUNDING` account per currency, valid debit/credit entry
types, positive entry amounts, required foreign keys, and immutability of
`ledger_transaction`/`ledger_entry` rows (`INSERT` allowed, `UPDATE`/
`DELETE` rejected).

**What the schema deliberately does not enforce** (left to the domain
service layer, per `CLAUDE.md` and `docs/ARCHITECTURE.md`): USD-only
account creation, deposits, and transfers (the `CHECK` only validates
currency *format*, not the specific code — see the migration's header
comment); insufficient-funds prevention beyond the blanket
`chk_account_balance_nonneg >= 0` floor (a transfer service that skipped
its own balance check would still be stopped by that constraint, but as a
raw constraint-violation error, not the clean 422 `InsufficientFundsException`
that `TransferService` produces by checking first); and the "total debits
equal total credits per transaction" trial-balance invariant (no deferred
constraint trigger exists for this; both `DepositService` and
`TransferService` enforce it by construction — each always writes exactly
one `DEBIT` and one `CREDIT` of the same amount and currency for every
transaction it creates — and their integration tests verify the resulting
rows are in fact balanced).

Application-side `AccountCategory`, `AccountClass`, and `AccountPurpose`
Java enums (Task 3) map 1:1 to the `account` table's string literals, via
`@Enumerated(EnumType.STRING)` on the `Account` entity. `TransactionType`,
`TransactionStatus`, and `LedgerEntryType` (Task 4) map the same way onto
`LedgerTransaction`/`LedgerEntry`.

## Idempotency Key Table (Flyway V2, Task 10)

A new, separate table — `V1` is untouched. One row per `Idempotency-Key`
value ever successfully claimed by a deposit or a transfer:

```sql
CREATE TABLE idempotency_key (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key         VARCHAR(128) NOT NULL,
    operation_type          VARCHAR(20) NOT NULL,
    primary_account_id      UUID NOT NULL REFERENCES account(id),
    secondary_account_id    UUID REFERENCES account(id),
    amount                  NUMERIC(19,4) NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    command_hash            CHAR(64) NOT NULL,
    ledger_transaction_id   UUID NOT NULL REFERENCES ledger_transaction(id),
    response_status         SMALLINT NOT NULL,
    response_body           TEXT NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT chk_idempotency_operation_type CHECK (operation_type IN ('DEPOSIT', 'TRANSFER')),
    CONSTRAINT chk_idempotency_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_idempotency_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_idempotency_response_status CHECK (response_status BETWEEN 200 AND 599),
    CONSTRAINT chk_idempotency_command_hash_format CHECK (command_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_idempotency_key_ledger_transaction_id ON idempotency_key (ledger_transaction_id);
```

- `idempotency_key` — the client-supplied header value, `UNIQUE` (a
  defense-in-depth backstop; the primary concurrency guarantee is a
  PostgreSQL transaction-scoped advisory lock — see
  `docs/ARCHITECTURE.md`'s "Idempotency" section), format-checked to match
  the same `^[A-Za-z0-9._:-]{1,128}$` pattern the controllers validate.
- `operation_type`, `primary_account_id`, `secondary_account_id`, `amount`,
  `currency` — the **canonical, normalized command** the key was first
  claimed for (`secondary_account_id` is null for a deposit; it's the
  transfer destination for a transfer). This is what a later request
  bearing the same key is compared against — exact-value comparison, never
  the hash alone.
- `command_hash` — a SHA-256 hex digest of the canonical command, stored
  for defense-in-depth/debugging only.
- `ledger_transaction_id` — `NOT NULL`, references the `ledger_transaction`
  row this key's operation produced.
- `response_status`, `response_body` — the exact original HTTP status and
  JSON response body, replayed byte-for-byte on every retry.
- `created_at` — UTC, database-assigned, same pattern as every other
  timestamp column in this project.

**Retention is indefinite in Phase 2** — no TTL column, no cleanup job, no
delete endpoint. **No sensitive data is stored** — only account ids,
amount, currency, a transaction reference, and the same response fields
already returned to the original caller.

`idempotency_key` is written by `idempotency.IdempotencyService`, exactly
once per key, as the last statement of the same `@Transactional` deposit/
transfer method that produced `ledger_transaction_id` — never updated
afterward.

## Outbox Event Table (Flyway V3, Task 11)

A new, separate table — `V1` and `V2` are untouched. One row per
completed deposit or transfer ledger transaction:

```sql
CREATE TABLE outbox_event (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type    VARCHAR(30) NOT NULL,
    aggregate_id      UUID NOT NULL REFERENCES ledger_transaction(id),
    event_type        VARCHAR(30) NOT NULL,
    schema_version    INTEGER NOT NULL,
    payload           JSONB NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,

    CONSTRAINT chk_outbox_aggregate_type CHECK (aggregate_type IN ('LEDGER_TRANSACTION')),
    CONSTRAINT chk_outbox_event_type CHECK (event_type IN ('DEPOSIT_COMPLETED', 'TRANSFER_COMPLETED')),
    CONSTRAINT chk_outbox_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT chk_outbox_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT uq_outbox_event_identity UNIQUE (aggregate_type, aggregate_id, event_type)
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (created_at, id)
    WHERE published_at IS NULL;
```

- `id` — the event id. Assigned in Java (`UUID.randomUUID()`, via
  `outbox.OutboxEventFactory`), not database-generated, so it is
  guaranteed identical to the `eventId` field inside `payload`.
- `aggregate_type`/`aggregate_id` — always `LEDGER_TRANSACTION` and the
  `ledger_transaction.id` the event describes in Task 11 (no `ON DELETE
  CASCADE` — an outbox row never silently disappears if its ledger
  transaction were ever deleted, which the Task 2 immutability trigger
  already prevents in practice).
- `event_type` — `DEPOSIT_COMPLETED` or `TRANSFER_COMPLETED`.
- `schema_version` — `1` for every event Task 11 produces; exists so a
  future payload shape change can be introduced as version `2` without
  breaking a reader of version `1` events already on the table.
- `payload` — the full version-1 event envelope, stored as native
  PostgreSQL `jsonb` (Hibernate's built-in `@JdbcTypeCode(SqlTypes.JSON)`,
  no additional dependency), constrained to always be a JSON *object*
  (`chk_outbox_payload_is_object`) — see "Version-1 Event Payloads" below
  for the exact field set.
- `occurred_at` — the same `Instant` as the referenced
  `ledger_transaction.created_at` (read back via
  `entityManager.refresh(transaction)` before the event is built — see
  `docs/ARCHITECTURE.md`'s "Transactional Outbox" section) — when the
  financial transaction actually happened, not when this row was written.
- `created_at` — UTC, database-assigned (`DEFAULT now()`), when this
  outbox row itself was persisted.
- `published_at` — `NULL` until Task 12's `OutboxPublisher` successfully
  publishes the row to Kafka and receives a broker acknowledgement; set to
  that moment's instant immediately afterward, and never before. The only
  mutation ever permitted on this table is this one column moving from
  `NULL` to non-null, enforced by `trg_outbox_event_immutable` (see
  "Outbox Event Immutability" below) — never cleared, never overwritten
  once set. `outbox.OutboxEventRepository.lockPendingById` (`SELECT ...
  FOR UPDATE SKIP LOCKED WHERE published_at IS NULL`) is what makes
  claiming a pending row safe across concurrent publishers, in one
  instance or across many — see `docs/ARCHITECTURE.md`'s "Kafka
  Publishing" section for the full mechanism.
- `uq_outbox_event_identity` (`UNIQUE (aggregate_type, aggregate_id,
  event_type)`) — at most one event of a given type per ledger
  transaction. A defense-in-depth database backstop; the primary guarantee
  that a Task 10 replay/conflict never creates a duplicate is structural
  (the insertion point is unreachable from those code paths — see
  `docs/ARCHITECTURE.md`).
- `idx_outbox_event_pending` — a partial index over unpublished rows only,
  ordered `(created_at, id)` for a deterministic, oldest-first scan —
  sized and shaped for a future publisher's poll, not used by anything in
  Task 11 itself. No attempt counts, retry timestamps, Kafka offsets,
  consumer state, or broker/partition/topic metadata are added — those
  belong to the task that actually publishes.

### Version-1 Event Payloads

`DEPOSIT_COMPLETED` (schema version 1):

```json
{
  "eventId": "uuid",
  "eventType": "DEPOSIT_COMPLETED",
  "schemaVersion": 1,
  "occurredAt": "2026-07-31T18:45:30.500000Z",
  "transactionId": "uuid",
  "destinationAccountId": "uuid",
  "amount": "100.0000",
  "currency": "USD"
}
```

`TRANSFER_COMPLETED` (schema version 1):

```json
{
  "eventId": "uuid",
  "eventType": "TRANSFER_COMPLETED",
  "schemaVersion": 1,
  "occurredAt": "2026-07-31T18:45:30.500000Z",
  "transactionId": "uuid",
  "sourceAccountId": "uuid",
  "destinationAccountId": "uuid",
  "amount": "30.0000",
  "currency": "USD"
}
```

Exactly these fields, no more — never the raw `Idempotency-Key`, a
password/token/credential/header, an account balance, a JPA entity, an
exception detail, a Java class name, a stack trace, or a database
constraint name. The deposit payload never includes the internal
`SYSTEM`/`EXTERNAL_FUNDING` account id. `amount` is always a JSON string
at the same four-decimal scale `DepositService`/`TransferService` already
normalize to for the ledger write itself — never a bare JSON number.
`occurredAt` is always an ISO-8601 UTC string. Both are built explicitly
by `outbox.OutboxEventFactory` rather than left to the application's
Jackson `ObjectMapper`'s default `BigDecimal`/`Instant` handling, so the
wire format is deterministic regardless of Jackson configuration.

### Outbox Event Immutability

`outbox_event` rows are part of the durable event record and are treated
the same way `ledger_entry`/`ledger_transaction` are (see "Ledger
Immutability" below), with one deliberate difference: a single narrow
mutation is permitted for the one column that exists specifically to
record a future state transition.

- `trg_outbox_event_no_delete` — a `BEFORE DELETE` trigger that
  unconditionally rejects deletion, unconditionally, just like the Task 2
  ledger triggers.
- `trg_outbox_event_immutable` — a `BEFORE UPDATE` trigger that rejects
  any change to `id`, `aggregate_type`, `aggregate_id`, `event_type`,
  `schema_version`, `payload`, `occurred_at`, or `created_at`; separately
  rejects any change to `published_at` once it is already non-null (no
  clearing, no overwriting). The only update this trigger ever allows is
  `published_at` moving from `NULL` to a non-null value, exactly once.

No application code path can attempt any other edit — `OutboxEvent` (the
JPA entity) exposes exactly one narrow mutator,
`markPublished(Instant)` (Task 12), used only by `outbox.OutboxPublisher`
after a successful Kafka acknowledgement — so these triggers are a
database-level guarantee on top of an application-level one, the same
reasoning `docs/ARCHITECTURE.md`'s "Ledger Immutability" section already
gives for why a trigger is used instead of relying on differentiated
database role grants (a single application database role in this
project).

## Processed Event Table (Flyway V4, Task 13)

A new, separate table — `V1`, `V2`, and `V3` are untouched. One row per
Kafka ledger event Task 13 has successfully processed, keyed by the
event's own stable `event_id` (Task 11's `outbox_event.id`):

```sql
CREATE TABLE processed_event (
    event_id           UUID PRIMARY KEY,
    aggregate_id        UUID NOT NULL,
    event_type          VARCHAR(30) NOT NULL,
    schema_version       INTEGER NOT NULL,
    payload_hash          CHAR(64) NOT NULL,
    source_topic          VARCHAR(255) NOT NULL,
    source_partition       INTEGER NOT NULL,
    source_offset          BIGINT NOT NULL,
    processed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_processed_event_type CHECK (event_type IN ('DEPOSIT_COMPLETED', 'TRANSFER_COMPLETED')),
    CONSTRAINT chk_processed_event_schema_version CHECK (schema_version = 1),
    CONSTRAINT chk_processed_event_payload_hash_format CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_processed_event_source_topic_nonblank CHECK (btrim(source_topic) <> ''),
    CONSTRAINT chk_processed_event_source_partition_nonneg CHECK (source_partition >= 0),
    CONSTRAINT chk_processed_event_source_offset_nonneg CHECK (source_offset >= 0),
    CONSTRAINT uq_processed_event_source_position UNIQUE (source_topic, source_partition, source_offset)
);
```

- **`event_id` is the primary key and the sole logical duplicate
  identity** — deliberately not Kafka topic/partition/offset. A
  legitimate at-least-once redelivery of the exact same event may land at
  a completely different Kafka position; if source coordinates were the
  identity, that ordinary redelivery would look like a brand new event
  and be processed (and "duplicated") again. `event_id` is what stays
  constant across any number of redeliveries.
- `aggregate_id`, `event_type`, `schema_version`, `payload_hash` — the
  canonical, comparable content of the event, checked against a
  redelivery with a matching `event_id` to distinguish an identical
  duplicate (safe no-op) from a conflicting reuse of the same id
  (rejected) — see `docs/ARCHITECTURE.md`'s "Kafka Consumption" section.
  `payload_hash` is a SHA-256 hex digest of the *exact* Kafka value
  string (never a reserialized form) — the full payload itself is not
  stored; the hash is deliberately the minimal fingerprint needed to
  detect a conflict, not a payload archive.
- `source_topic`/`source_partition`/`source_offset` — where this
  particular delivery was consumed from, recorded for diagnostics.
  `uq_processed_event_source_position` is a **corruption safeguard, not
  the deduplication mechanism**: it only prevents two *different*
  `event_id`s from ever claiming to have been read from the exact same
  Kafka position, which should never happen under correct redelivery. It
  deliberately does not constrain two different rows from sharing a
  `source_topic`/`source_partition` — only the specific
  `(topic, partition, offset)` triple must be unique.
- `processed_at` — UTC, database-assigned (`DEFAULT now()`), when this
  row was committed.
- **No foreign key to `ledger_transaction` or `outbox_event`.** The
  consumer boundary must not require direct access to producer-side rows
  to validate or process an event — `aggregate_id` is stored as a plain
  UUID value, not a JPA/foreign-key relationship.
- **No attempt counts, retry timestamps, error-message columns,
  dead-letter tracking, Kafka consumer-offset-management columns,
  settlement columns, or reconciliation columns.** Task 13's only retry
  signal is whether a row exists for a given `event_id` at all — nothing
  more granular is needed yet.

### Processed Event Immutability

`processed_event` is append-only, enforced the same way `V1`'s ledger
tables and `V3`'s `outbox_event` are: `trg_processed_event_no_update`
(`BEFORE UPDATE`) and `trg_processed_event_no_delete` (`BEFORE DELETE`)
both unconditionally raise an exception — `INSERT` remains the only
permitted operation, with no exception for `published_at`-style partial
mutation the way `outbox_event` has, since `processed_event` has no field
that is ever expected to change after insert.
`ProcessedEventRepository` (the JDBC-based repository — see
`docs/ARCHITECTURE.md`'s "Kafka Consumption" section for why it is
JDBC-based rather than JPA) exposes no update or delete method at all, so
no application code path can even attempt to violate this.

## Settlement Import Tables (Flyway V5, Task 14)

Two tables, added by `V5__add_settlement_import.sql`. `V1`–`V4` are
unmodified.

### `settlement_import`

One row per successfully committed whole-file import — inserted **once**,
with its final row counts already known, never inserted with placeholder
counts and updated afterward (see `docs/ARCHITECTURE.md`'s "Settlement
Import" section for why).

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key. |
| `source` | `VARCHAR(64)` | Display value as submitted (trimmed, not case-folded). |
| `normalized_source` | `VARCHAR(64)` | Lowercase form of `source`; part of both identities below. |
| `original_filename` | `VARCHAR(255)`, nullable | Sanitized basename only — audit metadata, never used for identity, parsing, or paths. |
| `file_hash` | `CHAR(64)` | Lowercase SHA-256 hex of the exact uploaded bytes, before BOM removal or decoding. |
| `file_size_bytes` | `BIGINT` | `> 0` and `<= 104857600` (a database-level sanity ceiling, independent of the smaller, configurable application limit). |
| `total_row_count` | `INTEGER` | `> 0`. |
| `inserted_row_count` | `INTEGER` | `>= 0`. |
| `duplicate_row_count` | `INTEGER` | `>= 0`; `inserted_row_count + duplicate_row_count = total_row_count` (`CHECK`). |
| `imported_at` | `TIMESTAMPTZ` | `DEFAULT now()`. |

Unique constraint `uq_settlement_import_source_file` on
`(normalized_source, file_hash)` — the logical file identity. A second
upload of the exact same bytes from the exact same normalized source
either replays the existing row (application layer) or, under a genuine
constraint bypass, is rejected outright by the database.

### `settlement_record`

One row per distinct settlement observation.

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key. |
| `normalized_source` | `VARCHAR(64)` | Part of the observation's identity. |
| `external_reference` | `VARCHAR(128)` | Non-blank; part of the observation's identity. |
| `transaction_id` | `UUID` | The LedgerGuard transaction id the external source reports. **No foreign key to `ledger_transaction`** — see below. |
| `amount` | `NUMERIC(19,2)` | `> 0`. Two decimal places, matching the CSV contract's required scale — not `NUMERIC(19,4)` like `account`/`ledger_entry`, since this column stores an *external report*, never compared against or combined with internal ledger amounts. |
| `currency` | `VARCHAR(3)` | Format-only `CHECK` (`^[A-Z]{3}$`), the same convention as `account`/`ledger_entry` — currency *support* (USD only) is an application-level check in `SettlementCsvParser`, not a database constraint. |
| `settled_at` | `TIMESTAMPTZ` | The external source's reported timestamp, stored as reported. |
| `row_hash` | `CHAR(64)` | SHA-256 hex of the canonical, length-prefixed encoding of every business field above (see `docs/ARCHITECTURE.md`). |
| `first_import_id` | `UUID` | The import that first created this row. Foreign key to `settlement_import(id)`, **`DEFERRABLE INITIALLY DEFERRED`** — see below. |
| `source_row_number` | `INTEGER` | `> 0`; the 1-based data-row number within its originating file. |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT now()`. |

Unique constraint `uq_settlement_record_source_reference` on
`(normalized_source, external_reference)` — the logical observation
identity, and the constraint `SettlementRecordRepository.tryClaim(...)`'s
`INSERT ... ON CONFLICT DO NOTHING` targets.

**Why no foreign key to `ledger_transaction`:** a settlement row may
legitimately reference a transaction UUID that does not (yet, or ever)
exist in LedgerGuard — an unmatched external transaction is itself
important evidence for future reconciliation (Task 15), not invalid data.
A foreign key would incorrectly reject exactly the rows this table exists
to retain, so `transaction_id` is stored as a plain `UUID` value with no
referential-integrity relationship, the same pattern `processed_event`
already established for `aggregate_id` (Task 13).

**Why the foreign key to `settlement_import` is deferred:**
`SettlementImportProcessor` claims every `settlement_record` row for a
new import *before* it knows the import's final row counts (it must
finish counting first, since `settlement_import` is append-only and is
never updated after insert) — so those row claims reference
`first_import_id` before that row exists yet. `DEFERRABLE INITIALLY
DEFERRED` moves the constraint check from per-statement to per-commit, so
this within-transaction ordering is valid; the constraint is still fully
enforced by the time any other transaction can observe the data.

**No settlement status, reconciliation-result, match/mismatch, retry, or
error-message columns; no raw file or raw row storage; no settlement
ledger entries or adjustment records; no reconciliation-result table.**
Task 14 is limited to durably recording the external observation itself.

### Settlement Table Immutability

Both tables are append-only, in the same style as `V1`/`V3`/`V4`:
`trg_settlement_import_no_update`/`trg_settlement_import_no_delete` and
`trg_settlement_record_no_update`/`trg_settlement_record_no_delete`
(`BEFORE UPDATE`/`BEFORE DELETE`) unconditionally raise an exception.
`SettlementImportRepository` and `SettlementRecordRepository` (both
JDBC-based, the same reason as `ProcessedEventRepository`) expose no
update or delete method at all.

## Settlement Reconciliation Tables (Flyway V6, Task 15)

Two tables, added by `V6__add_settlement_reconciliation.sql`. `V1`–`V5`
are unmodified.

### `reconciliation_run`

One row per `(settlement_import_id, algorithm_version)` ever committed —
inserted **once**, with its final result counts already known (computed
before the row is inserted), never inserted with placeholder counts and
updated afterward.

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key. |
| `settlement_import_id` | `UUID` | Foreign key to `settlement_import(id)`. |
| `algorithm_version` | `INTEGER` | `> 0`. Task 15 always writes `1` — no public API selects a version. |
| `total_result_count` | `INTEGER` | `>= 0`. May legitimately be `0` (an all-duplicate import). |
| `matched_count` | `INTEGER` | `>= 0`. |
| `discrepancy_count` | `INTEGER` | `>= 0`. Every outcome except `MATCHED` and `INTERNAL_LEDGER_INCONSISTENT`. |
| `inconsistent_count` | `INTEGER` | `>= 0`. `INTERNAL_LEDGER_INCONSISTENT` only. |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT now()`. |

`CHECK (matched_count + discrepancy_count + inconsistent_count = total_result_count)`.
Unique constraint `uq_reconciliation_run_settlement_import_algorithm_version`
on `(settlement_import_id, algorithm_version)` — **not**
`settlement_import_id` alone, so a future, separately-approved algorithm
version can produce a new immutable run against the same import without
being rejected as a duplicate of version 1, while a repeated command for
the exact same `(import, version)` pair replays the existing row.

`importedFileRows`/`newlyRecordedObservations`/`duplicateRows` (the
response summary's disambiguating fields — see
`docs/ARCHITECTURE.md`'s "Settlement Reconciliation" section) are
deliberately **not** duplicated onto this table — they are exactly
`settlement_import.total_row_count`/`inserted_row_count`/
`duplicate_row_count`, already stored immutably there and read via a
join.

### `reconciliation_result`

One row per settlement observation a run reconciled.

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key. |
| `run_id` | `UUID` | Foreign key to `reconciliation_run(id)`. |
| `settlement_record_id` | `UUID` | Foreign key to `settlement_record(id)`. |
| `reported_transaction_id` | `UUID` | Copied from `settlement_record.transaction_id` at result-creation time. |
| `outcome` | `VARCHAR(40)` | One of the seven approved values (`CHECK`). |
| `reported_amount` | `NUMERIC(19,2)` | Snapshotted from `settlement_record.amount` — matches its scale exactly. |
| `reported_currency` | `VARCHAR(3)` | Snapshotted from `settlement_record.currency`. |
| `internal_amount` | `NUMERIC(19,4)`, nullable | Snapshotted from the validated `ledger_entry` pair — matches its scale exactly. `NULL` for `INTERNAL_TRANSACTION_NOT_FOUND`, `INELIGIBLE_TRANSACTION_TYPE`, and `INTERNAL_LEDGER_INCONSISTENT`. |
| `internal_currency` | `VARCHAR(3)`, nullable | `NULL` exactly when `internal_amount` is `NULL` (`CHECK`). |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT now()`. |

Unique constraint `uq_reconciliation_result_run_settlement_record` on
`(run_id, settlement_record_id)` — one result per observation per run.
`reported_amount`/`reported_currency`/`internal_amount`/`internal_currency`
are **copied**, not just referenced by id — both `settlement_record` and
`ledger_entry` are themselves immutable, so snapshotting is safe and
makes a historical result independently interpretable without a join.
**No raw CSV, no raw ledger row, no complete error message, no account
balance, and no mutable status field is stored here** — the outcome code
itself is the complete, safe classification signal.

### Settlement Reconciliation Table Immutability

Both tables are append-only, in the same style as `V1`/`V3`/`V4`/`V5`:
`trg_reconciliation_run_no_update`/`trg_reconciliation_run_no_delete` and
`trg_reconciliation_result_no_update`/`trg_reconciliation_result_no_delete`
(`BEFORE UPDATE`/`BEFORE DELETE`) unconditionally raise an exception.
`ReconciliationRunRepository` and `ReconciliationResultRepository` (both
JDBC-based, the same reason as `ProcessedEventRepository`) expose no
update or delete method at all.

## Customer Ownership and Principal-Scoped Idempotency (V7, Task 17)

`V7__add_customer_ownership_and_idempotency_principal.sql` makes two
additive changes, both required by stateless authentication, neither
touching `V1`–`V6`:

**`account.customer_subject VARCHAR(255)`** — the authenticated JWT
subject that owns a `CUSTOMER` wallet. Constrained by
`chk_account_ownership`:
```sql
(account_category = 'CUSTOMER' AND customer_subject IS NOT NULL)
OR (account_category = 'SYSTEM' AND customer_subject IS NULL)
```
Never populated from client-supplied JSON — `CreateAccountRequest` has no
field for it; `AccountService` sources it exclusively from the validated
JWT. Existing `CUSTOMER` rows (created before authentication existed) are
backfilled to a single fixed, documented sentinel,
`'legacy-unowned-customer'` — there is no way to recover true historical
ownership, and a fixed, searchable sentinel is preferable to a guess. No
new index is added: ownership is checked by loading an account by its
primary key and comparing the already-loaded row's `customer_subject`,
which the primary key index already serves — an index on
`customer_subject` itself would be speculative, justified only by a
hypothetical future "list my accounts" endpoint that is out of scope.

**`idempotency_key.principal_subject VARCHAR(255) NOT NULL`** — closes a
genuine pre-existing gap: uniqueness on `idempotency_key` alone (V2) is
global, with no notion of which caller claimed it. Once authentication
exists, two different customers reusing the same literal key string would
otherwise collide — the second customer's request would either replay
the first customer's stored response (cross-customer leakage) or
409-conflict against a key they never chose. `V7` changes the unique
constraint from `uq_idempotency_key` to
`uq_idempotency_key_principal UNIQUE (principal_subject, idempotency_key)`.
Backfill is narrow and deliberately has no fallback: `principal_subject`
is populated only by joining to the row's own `primary_account_id`'s
(already-backfilled) `customer_subject`; if any row cannot be mapped this
way, the subsequent `SET NOT NULL` fails the migration outright rather
than silently mapping a possibly-corrupted row to a shared sentinel,
which could otherwise conceal a real data-integrity defect.
`IdempotencyCommand.advisoryLockId` is likewise now keyed on
`(principalSubject, idempotencyKey)`, not the raw key alone, so two
principals choosing the same literal key string never unnecessarily
serialize against each other. See `docs/ARCHITECTURE.md`'s
"Authentication and Authorization" section for how ownership is enforced
ahead of any idempotency claim/replay.

## Account Creation Enforcement (implemented, Task 3)

`POST /api/v1/accounts` only ever inserts rows with
`(CUSTOMER, LIABILITY, CUSTOMER_WALLET)` — `AccountService` constructs this
combination directly; it is never read from the request. `balance` is
forced to `0.0000` in the same way. `ownerName` and `currency` are the only
client-supplied values; `currency` must be `USD` (case-insensitive input is
normalized to uppercase), enforced in `AccountService`, not the database
(the DB's `chk_account_currency_format` still just checks the ISO-4217
shape, per the header comment in `V1__init_account_ledger_schema.sql`).
`created_at` is populated by the database's `DEFAULT now()`, not by
application code.

## Deposit Enforcement (implemented, Task 4)

`DepositRequest` has fields for `amount` and `currency` only. `DepositService`
resolves the funding account purely from its taxonomy and the request
currency — never from a client-supplied id — and constructs the
`ledger_transaction`/`ledger_entry` rows directly:
`transaction_type = DEPOSIT`, `status = COMPLETED`, one `DEBIT` entry
against the funding account and one `CREDIT` entry against the destination
account, both for the exact validated amount, both `currency = 'USD'`. None
of these values are read from the request. `amount` is validated with
`@NotNull @Positive @Digits(integer = 15, fraction = 4)` before the service
ever runs, matching `ledger_entry.amount`'s and `account.balance`'s
`NUMERIC(19,4)` shape exactly, so an out-of-range or malformed amount is a
clean 400 rather than a database error. The destination account must
already be `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` and `USD` — checked in
`DepositService` against the locked row before any ledger write happens.

## Transfer Enforcement (implemented, Task 5)

`TransferRequest` has fields for `sourceAccountId`, `destinationAccountId`,
`amount`, and `currency` only. `TransferService` constructs the
`ledger_transaction`/`ledger_entry` rows directly:
`transaction_type = TRANSFER`, `status = COMPLETED`, one `DEBIT` entry
against the source account and one `CREDIT` entry against the destination
account, both for the exact validated amount, both `currency = 'USD'`.
Both accounts must already be `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` and
`USD`, checked against the locked rows before any ledger write happens —
same as deposits. `sourceAccountId == destinationAccountId` is rejected
before either account is even looked up.

Insufficient funds (`source.balance < amount`) is checked in
`TransferService` immediately after both accounts are locked and validated,
and before any `ledger_transaction`/`ledger_entry` row is written — so a
rejected transfer never gets as far as constructing ledger rows it would
then have to roll back. A transfer for exactly the source's current
balance is allowed (`balance >= amount` including equality) and correctly
leaves the source at exactly `0.0000`, still satisfying
`chk_account_balance_nonneg`.

## Account Balance and Transaction History Enforcement (implemented, Task 6)

`GET /api/v1/accounts/{id}/balance` returns `account.balance` — the
persisted materialized column — read via a single `SELECT`, with no
`FOR UPDATE` lock (reads don't need one; see `docs/ARCHITECTURE.md`'s
"Account Balance and Transaction History" section for why) and no
recomputation from `ledger_entry`. The database's own guarantees
(`chk_account_balance_nonneg`, and every write path updating this column
only alongside balanced ledger entries in one transaction) are what make
this plain read trustworthy without Task 6 needing to verify anything
itself.

`GET /api/v1/accounts/{id}/transactions` reads `ledger_entry` rows filtered
by `account_id = {id}`, ordered `created_at DESC, id DESC` (the approved
Task 6 ordering — see `docs/API_SPEC.md`). Because the filter is on
`account_id`, the account's own entries are the only ones a query can ever
return — there's no separate step that could accidentally include or leak
a counterparty's or an unrelated account's row. Immutability
(`docs/ARCHITECTURE.md`'s "Ledger Immutability") is what makes this
history safe to serve directly: the rows a `GET` reads cannot have been
altered since they were written by a deposit or transfer, so there's no
"stale vs. current" distinction to worry about — the row as written is the
row as read, always.
