# Data Model

> **Status: schema implemented (Task 2); account creation (Task 3) and
> deposits (Task 4) implemented.** The tables, constraints, indexes, and
> triggers described below exist in the database via
> `src/main/resources/db/migration/V1__init_account_ledger_schema.sql`
> and are verified by `SchemaMigrationIntegrationTest`. `account` has a
> matching JPA entity used by both account creation and deposits.
> `ledger_transaction`/`ledger_entry` now have matching JPA entities too
> (`LedgerTransaction`, `LedgerEntry`), written only by deposit processing
> so far. Transfers, balance lookups, and transaction history remain
> unimplemented (Tasks 5–6).

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
- **Transfer** (not yet implemented): DEBIT sender (liability) / CREDIT
  recipient (liability).
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
account creation and deposits (the `CHECK` only validates currency
*format*, not the specific code — see the migration's header comment), and
the "total debits equal total credits per transaction" trial-balance
invariant (no deferred constraint trigger exists for this; `DepositService`
enforces it by construction — it always writes exactly one `DEBIT` and one
`CREDIT` of the same amount and currency for every transaction it creates —
and `DepositIntegrationTest` verifies the resulting rows are in fact
balanced).

Application-side `AccountCategory`, `AccountClass`, and `AccountPurpose`
Java enums (Task 3) map 1:1 to the `account` table's string literals, via
`@Enumerated(EnumType.STRING)` on the `Account` entity. `TransactionType`,
`TransactionStatus`, and `LedgerEntryType` (Task 4) map the same way onto
`LedgerTransaction`/`LedgerEntry`.

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
