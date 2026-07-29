# Data Model

> **Status: planning document.** Describes the approved Phase 1 schema and
> accounting semantics. **No Flyway migrations exist yet** — this schema is
> not implemented in the database. It will be created in Task 2.

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

## Planned Schema (Flyway, Task 2 — not yet created)

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
tables (see `docs/ARCHITECTURE.md`).

Application-side `AccountCategory`, `AccountClass`, `AccountPurpose`,
`TransactionType`, `TransactionStatus`, and `LedgerEntryType` Java enums are
planned to map 1:1 to these string literals — none of these types exist yet.
