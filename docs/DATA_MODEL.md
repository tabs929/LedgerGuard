# Data Model

> **Status: `account`/`ledger_transaction`/`ledger_entry` schema
> implemented (Task 2, Flyway V1); account creation (Task 3), deposits
> (Task 4), transfers (Task 5), account balance/transaction-history reads
> (Task 6), idempotency for deposits/transfers (Task 10, Flyway V2), and
> the transactional outbox (Task 11, Flyway V3) implemented.** The `V1`
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
- `published_at` — `NULL` for every row Task 11 ever writes. Reserved for
  a future publisher task; the only mutation ever permitted on this table
  is this one column moving from `NULL` to non-null, enforced by
  `trg_outbox_event_immutable` (see "Outbox Event Immutability" below) —
  never cleared, never overwritten once set.
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

No application code path can even attempt an edit — `OutboxEvent` (the
JPA entity) exposes no setters — so these triggers are a database-level
guarantee on top of an application-level one, the same reasoning
`docs/ARCHITECTURE.md`'s "Ledger Immutability" section already gives for
why a trigger is used instead of relying on differentiated database role
grants (a single application database role in this project).

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
