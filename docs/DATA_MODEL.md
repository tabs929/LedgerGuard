# Data Model

> **Status: schema implemented (Task 2); account creation implemented
> (Task 3).** The tables, constraints, indexes, and triggers described
> below exist in the database via
> `src/main/resources/db/migration/V1__init_account_ledger_schema.sql`
> and are verified by `SchemaMigrationIntegrationTest`. The `account` table
> now has a matching JPA entity (`Account`, `AccountRepository`) used by
> account creation only — `POST /api/v1/accounts` creates
> `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` rows with a zero balance. No Java
> code yet reads or writes `ledger_transaction` or `ledger_entry` — deposits,
> transfers, balance lookups, and transaction history remain unimplemented
> (Tasks 4–6).

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

- **Deposit:** DEBIT `EXTERNAL_FUNDING` (asset) / CREDIT customer account
  (liability).
- **Transfer:** DEBIT sender (liability) / CREDIT recipient (liability).
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

**What the schema deliberately does not enforce** (left to the future
domain service, per `CLAUDE.md` and `docs/ARCHITECTURE.md`):
USD-only account creation (the `CHECK` only validates currency *format*,
not the specific code — see the migration's header comment), and the
"total debits equal total credits per transaction" trial-balance
invariant (no deferred constraint trigger exists for this; it is enforced
by the future `@Transactional` service boundary and verified by
integration tests once that service exists).

Application-side `AccountCategory`, `AccountClass`, and `AccountPurpose`
Java enums (Task 3) map 1:1 to the `account` table's string literals, via
`@Enumerated(EnumType.STRING)` on the `Account` entity.
`TransactionType`, `TransactionStatus`, and `LedgerEntryType` remain
unimplemented — no code writes to `ledger_transaction` or `ledger_entry`
yet.

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
