-- LedgerGuard Phase 1, Task 2: initial account and double-entry ledger schema.
--
-- This migration creates the database-level structure only. It does not
-- implement business logic. In particular:
--   * The "total debits equal total credits per transaction" invariant is
--     NOT enforced here by a trigger. Per docs/DATA_MODEL.md, PostgreSQL
--     does not automatically balance ledger_entry rows across a
--     transaction_id; that invariant is enforced by the future domain
--     service transaction boundary (Task 4/5) and verified by integration
--     tests, not by a database trigger. Adding such a trigger now would be
--     premature, since the domain service does not exist yet.
--   * Phase 1 restricts account creation to USD only, but that restriction
--     is an application-level validation rule (Task 3), not a database
--     constraint. The schema stays currency-format-agnostic (any 3
--     uppercase-letter ISO 4217-shaped code passes the CHECK constraint)
--     so that supporting an additional currency later is a migration +
--     validation-list change, not a schema redesign. See docs/DATA_MODEL.md.

-- =============================================================================
-- account
-- =============================================================================
--
-- Account taxonomy is described by three columns, not one:
--   account_category: who owns the account            (CUSTOMER | SYSTEM)
--   account_class:     normal-balance side              (ASSET | LIABILITY)
--   account_purpose:   what the account is for          (CUSTOMER_WALLET | EXTERNAL_FUNDING)
--
-- Only two (category, class, purpose) combinations are valid in Phase 1:
--   CUSTOMER + LIABILITY + CUSTOMER_WALLET
--   SYSTEM   + ASSET      + EXTERNAL_FUNDING
--
-- Individual per-column CHECK constraints cannot stop invalid *combinations*
-- (e.g. CUSTOMER + ASSET + EXTERNAL_FUNDING), so a cross-column CHECK
-- constraint (chk_account_taxonomy_combination) enumerates the only
-- combinations allowed today.
--
-- IMPORTANT: introducing a new account_purpose in the future (e.g. a fee or
-- suspense account) requires a NEW Flyway migration that widens
-- chk_account_taxonomy_combination (and chk_account_purpose). This
-- constraint is not extensible without a schema change by design.

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

    -- ISO 4217-shaped currency code (three uppercase letters). Not a
    -- USD-only constraint by design -- see header comment above.
    CONSTRAINT chk_account_currency_format CHECK (currency ~ '^[A-Z]{3}$'),

    -- Materialized balance is never negative. In Phase 1 no operation
    -- drives the EXTERNAL_FUNDING asset balance negative (deposits only
    -- increase it), so this floor applies uniformly to every account,
    -- customer or system.
    CONSTRAINT chk_account_balance_nonneg CHECK (balance >= 0),

    -- Only the two account-taxonomy combinations valid in Phase 1.
    -- Extending this to a new SYSTEM purpose requires a future migration.
    CONSTRAINT chk_account_taxonomy_combination CHECK (
        (account_category = 'CUSTOMER' AND account_class = 'LIABILITY' AND account_purpose = 'CUSTOMER_WALLET')
        OR
        (account_category = 'SYSTEM' AND account_class = 'ASSET' AND account_purpose = 'EXTERNAL_FUNDING')
    )
);

-- At most one SYSTEM account per (purpose, currency). Scoped to SYSTEM
-- accounts only (via the partial WHERE clause) so this never restricts how
-- many CUSTOMER_WALLET accounts can exist per currency, and leaves room to
-- add other SYSTEM purposes for the same currency later without
-- redesigning the constraint.
CREATE UNIQUE INDEX uq_system_account_purpose_currency
    ON account (account_purpose, currency)
    WHERE account_category = 'SYSTEM';

-- =============================================================================
-- ledger_transaction
-- =============================================================================
--
-- The transaction header. Groups the ledger_entry rows produced by one
-- business operation. Immutable once written (see triggers below).

CREATE TABLE ledger_transaction (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type    VARCHAR(30) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('DEPOSIT', 'TRANSFER')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('COMPLETED'))
);

-- =============================================================================
-- ledger_entry
-- =============================================================================
--
-- Debit/credit postings belonging to a ledger_transaction. ledger_entry has
-- no schema-level restriction to exactly two rows per transaction_id -- the
-- domain rule (>= 2 entries per transaction, total debits == total credits
-- per currency) is enforced by the future domain service transaction
-- boundary, not by the database. This keeps the model open to future
-- transaction types that post more than two entries, without a schema
-- change. Immutable once written (see triggers below).

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

-- Every read of "the ledger for this account" (balance recomputation,
-- transaction history) filters by account_id and orders by created_at.
CREATE INDEX idx_ledger_entry_account_id ON ledger_entry (account_id, created_at);

-- Every read of "the entries that make up this transaction" (trial-balance
-- checks, transaction detail) filters by transaction_id.
CREATE INDEX idx_ledger_entry_transaction_id ON ledger_entry (transaction_id);

-- =============================================================================
-- Ledger immutability
-- =============================================================================
--
-- ledger_transaction and ledger_entry are both part of the authoritative
-- financial record (see docs/ARCHITECTURE.md). Both get a
-- BEFORE UPDATE OR DELETE trigger that unconditionally rejects mutation.
-- This is a database-level guard rather than differentiated role grants,
-- because the project currently uses a single application database role --
-- a grant-based approach would provide no real protection today. INSERT
-- remains allowed (triggers only fire on UPDATE OR DELETE).

CREATE OR REPLACE FUNCTION reject_ledger_transaction_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_transaction rows are immutable: % not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_transaction_immutable
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_transaction_mutation();

CREATE OR REPLACE FUNCTION reject_ledger_entry_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry rows are immutable: % not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_mutation();

-- =============================================================================
-- Seed data
-- =============================================================================
--
-- The single system funding account required for Phase 1 (USD only).
-- Starts at balance 0 -- no fake seed money. No customer accounts or other
-- example data are seeded; none are required by the approved documentation.

INSERT INTO account (account_category, account_class, account_purpose, owner_name, currency, balance)
VALUES ('SYSTEM', 'ASSET', 'EXTERNAL_FUNDING', 'EXTERNAL_FUNDING', 'USD', 0);
