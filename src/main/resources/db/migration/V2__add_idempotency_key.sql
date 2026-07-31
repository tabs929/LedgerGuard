-- LedgerGuard Phase 2, Task 10: idempotency for deposits and transfers.
--
-- One row per Idempotency-Key ever successfully claimed by a deposit or a
-- transfer. Retention is indefinite in Phase 2 -- no TTL column, no cleanup
-- job, no delete endpoint (see docs/DATA_MODEL.md). No sensitive data is
-- stored: only account ids, amount, currency, a link to the resulting
-- ledger_transaction, and the exact JSON response already returned to the
-- original caller.
--
-- The application is the sole writer of this table (via
-- idempotency.IdempotencyService); PostgreSQL's UNIQUE constraint on
-- idempotency_key is a defense-in-depth backstop, not the primary
-- concurrency mechanism -- the primary mechanism is a transaction-scoped
-- advisory lock (pg_advisory_xact_lock) keyed on a hash of the
-- Idempotency-Key value, acquired before this table or any account row is
-- touched. See docs/ARCHITECTURE.md's "Idempotency" section.

CREATE TABLE idempotency_key (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key         VARCHAR(128) NOT NULL,
    operation_type          VARCHAR(20) NOT NULL,

    -- Canonical, normalized command data used for exact conflict/replay
    -- comparison -- never relying solely on command_hash below.
    -- primary_account_id is the deposit destination or the transfer
    -- source; secondary_account_id is null for a deposit and the transfer
    -- destination for a transfer.
    primary_account_id     UUID NOT NULL REFERENCES account(id),
    secondary_account_id   UUID REFERENCES account(id),
    amount                  NUMERIC(19,4) NOT NULL,
    currency                VARCHAR(3) NOT NULL,

    -- SHA-256 hex digest of the canonical command string. Defense-in-depth
    -- integrity check only -- exact comparison always uses the canonical
    -- columns above, per CLAUDE.md/Task 10's explicit requirement.
    command_hash             CHAR(64) NOT NULL,

    -- The financial transaction this key's operation produced, and the
    -- exact original response returned to the caller, for byte-for-byte
    -- replay on every retry.
    ledger_transaction_id    UUID NOT NULL REFERENCES ledger_transaction(id),
    response_status          SMALLINT NOT NULL,
    response_body            TEXT NOT NULL,

    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT chk_idempotency_operation_type CHECK (operation_type IN ('DEPOSIT', 'TRANSFER')),
    CONSTRAINT chk_idempotency_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_idempotency_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_idempotency_response_status CHECK (response_status BETWEEN 200 AND 599),
    CONSTRAINT chk_idempotency_command_hash_format CHECK (command_hash ~ '^[0-9a-f]{64}$')
);

-- Supports "which key(s) produced this transaction" lookups and keeps the
-- foreign key indexed (Postgres does not index FK columns automatically).
CREATE INDEX idx_idempotency_key_ledger_transaction_id ON idempotency_key (ledger_transaction_id);
