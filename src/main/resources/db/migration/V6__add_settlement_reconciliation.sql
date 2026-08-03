-- LedgerGuard Phase 2, Task 15: settlement reconciliation.
--
-- Compares immutable Task 14 settlement observations against LedgerGuard's
-- own immutable ledger and durably records the classification. This
-- migration adds persistence only: no ledger mutation, no balance
-- mutation, no mutation of settlement_import/settlement_record, no
-- outbox event, no Kafka publish. See reconciliation.ReconciliationProcessor
-- and docs/ARCHITECTURE.md's "Settlement Reconciliation" section for the
-- exact matching algorithm and transaction/concurrency model.
--
-- A reconciliation run reconciles the settlement_record rows FIRST
-- CREATED by one settlement_import (settlement_record.first_import_id =
-- settlement_import.id) -- not every logical row the uploaded file
-- contained. A row that file duplicated from an earlier import belongs to
-- its original observation and original import; it produces no additional
-- reconciliation_result row here. An import containing only
-- previously-known duplicate rows legitimately produces a zero-result run
-- (total_result_count = 0 is valid, not an error -- see the CHECK
-- constraints below, none of which require a positive count).
--
-- Two tables:
--   * reconciliation_run    -- one row per (settlement_import, algorithm
--     version) ever committed. Append-only, exactly like every other
--     evidence/audit table in this project.
--   * reconciliation_result -- one row per settlement_record reconciled
--     by a given run, classified into exactly one outcome.

-- =============================================================================
-- reconciliation_run
-- =============================================================================
--
-- Logical identity is (settlement_import_id, algorithm_version) -- not
-- settlement_import_id alone -- so a future, separately-approved algorithm
-- version can produce a new immutable run against the same import without
-- being rejected as a duplicate, while a repeated request for the exact
-- same (import, version) pair replays the existing committed run instead
-- of computing a second "true" answer for the same question (Task 15 uses
-- algorithm_version = 1 only; there is no API to select a version).
--
-- total_result_count/matched_count/discrepancy_count/inconsistent_count
-- are computed by the caller BEFORE this row is inserted (the whole
-- proposed result set is computed first, then the run identity is
-- atomically claimed via INSERT ... ON CONFLICT DO NOTHING -- see
-- reconciliation.ReconciliationProcessor) -- so, like V5's
-- settlement_import, this row is never inserted with placeholder counts
-- and updated afterward.
--
-- importedFileRows/newlyRecordedObservations/duplicateRows (Task 15's
-- response summary) are deliberately NOT duplicated onto this table --
-- they are exactly settlement_import.total_row_count/inserted_row_count/
-- duplicate_row_count, already stored immutably there and read via a
-- join, so this table only stores what Task 15 itself computes.

CREATE TABLE reconciliation_run (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    settlement_import_id   UUID NOT NULL REFERENCES settlement_import(id),
    algorithm_version      INTEGER NOT NULL,

    total_result_count      INTEGER NOT NULL,
    matched_count             INTEGER NOT NULL,
    discrepancy_count          INTEGER NOT NULL,
    inconsistent_count          INTEGER NOT NULL,

    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_reconciliation_run_algorithm_version_positive CHECK (algorithm_version > 0),
    CONSTRAINT chk_reconciliation_run_total_result_count_nonneg CHECK (total_result_count >= 0),
    CONSTRAINT chk_reconciliation_run_matched_count_nonneg CHECK (matched_count >= 0),
    CONSTRAINT chk_reconciliation_run_discrepancy_count_nonneg CHECK (discrepancy_count >= 0),
    CONSTRAINT chk_reconciliation_run_inconsistent_count_nonneg CHECK (inconsistent_count >= 0),
    -- Every result belongs to exactly one of these three buckets --
    -- MATCHED is "matched"; INTERNAL_TRANSACTION_NOT_FOUND,
    -- INELIGIBLE_TRANSACTION_TYPE, AMOUNT_MISMATCH, CURRENCY_MISMATCH, and
    -- AMOUNT_AND_CURRENCY_MISMATCH are all "discrepancy";
    -- INTERNAL_LEDGER_INCONSISTENT is "inconsistent". A zero-result run
    -- (total_result_count = 0) satisfies this trivially with all four
    -- counts at zero -- explicitly valid, not excluded.
    CONSTRAINT chk_reconciliation_run_counts_consistent
        CHECK (matched_count + discrepancy_count + inconsistent_count = total_result_count),

    -- The logical run identity. A repeated command for the same
    -- (import, algorithm version) claims nothing new -- see
    -- reconciliation.ReconciliationRunRepository -- and the existing row
    -- is returned as a replay.
    CONSTRAINT uq_reconciliation_run_settlement_import_algorithm_version
        UNIQUE (settlement_import_id, algorithm_version)
);

-- =============================================================================
-- reconciliation_result
-- =============================================================================
--
-- One row per settlement_record reconciled by a given run. reported_amount/
-- reported_currency are copied (snapshotted) from settlement_record at
-- result-creation time rather than only referenced by foreign key, so a
-- result row is independently interpretable without a join and immune to
-- any future change in how settlement_record is read. internal_amount/
-- internal_currency are similarly snapshotted from the immutable
-- ledger_entry rows that justified them -- immutable source, so
-- snapshotting is safe and avoids re-joining ledger_entry on every
-- historical read. Both are NULL together exactly when no trustworthy
-- internal value exists (INTERNAL_TRANSACTION_NOT_FOUND or
-- INTERNAL_LEDGER_INCONSISTENT); populated together for every other
-- outcome (see the CHECK constraint below).
--
-- No raw CSV, no raw ledger row, no complete error message, no account
-- balance, and no mutable status field is stored here -- the outcome code
-- itself is the complete, safe classification signal.

CREATE TABLE reconciliation_result (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    run_id                    UUID NOT NULL REFERENCES reconciliation_run(id),
    settlement_record_id      UUID NOT NULL REFERENCES settlement_record(id),

    -- Copied from settlement_record.transaction_id at result-creation
    -- time -- the externally reported transaction id this result is
    -- about.
    reported_transaction_id    UUID NOT NULL,

    outcome                      VARCHAR(40) NOT NULL,

    -- Snapshotted from settlement_record (NUMERIC(19,2), matching V5's
    -- settlement_record.amount/currency exactly).
    reported_amount                NUMERIC(19,2) NOT NULL,
    reported_currency                VARCHAR(3) NOT NULL,

    -- Snapshotted from the validated internal ledger_entry pair
    -- (NUMERIC(19,4), matching V1's ledger_entry.amount/currency exactly).
    -- NULL together when no trustworthy internal value exists.
    internal_amount                    NUMERIC(19,4),
    internal_currency                    VARCHAR(3),

    created_at                             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_reconciliation_result_outcome CHECK (outcome IN (
        'MATCHED',
        'INTERNAL_TRANSACTION_NOT_FOUND',
        'INELIGIBLE_TRANSACTION_TYPE',
        'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH',
        'AMOUNT_AND_CURRENCY_MISMATCH',
        'INTERNAL_LEDGER_INCONSISTENT'
    )),
    CONSTRAINT chk_reconciliation_result_reported_amount_positive CHECK (reported_amount > 0),
    CONSTRAINT chk_reconciliation_result_reported_currency_format CHECK (reported_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_reconciliation_result_internal_amount_positive
        CHECK (internal_amount IS NULL OR internal_amount > 0),
    CONSTRAINT chk_reconciliation_result_internal_currency_format
        CHECK (internal_currency IS NULL OR internal_currency ~ '^[A-Z]{3}$'),
    -- internal_amount and internal_currency are populated exactly
    -- together -- never one without the other.
    CONSTRAINT chk_reconciliation_result_internal_pair_together
        CHECK ((internal_amount IS NULL) = (internal_currency IS NULL)),

    -- One result per settlement_record per run -- a given observation
    -- cannot appear twice in the same run's output. This composite
    -- index's leftmost column (run_id) also serves paginated
    -- per-run result retrieval; no separate run_id-only index is added.
    CONSTRAINT uq_reconciliation_result_run_settlement_record UNIQUE (run_id, settlement_record_id)
);

-- =============================================================================
-- Append-only enforcement
-- =============================================================================
--
-- Same BEFORE UPDATE/BEFORE DELETE-rejecting trigger style as V1's ledger
-- tables, V3's outbox_event, V4's processed_event, and V5's settlement
-- tables.

CREATE OR REPLACE FUNCTION reject_reconciliation_run_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation_run rows are immutable: UPDATE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reconciliation_run_no_update
    BEFORE UPDATE ON reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION reject_reconciliation_run_update();

CREATE OR REPLACE FUNCTION reject_reconciliation_run_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation_run rows are immutable: DELETE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reconciliation_run_no_delete
    BEFORE DELETE ON reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION reject_reconciliation_run_delete();

CREATE OR REPLACE FUNCTION reject_reconciliation_result_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation_result rows are immutable: UPDATE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reconciliation_result_no_update
    BEFORE UPDATE ON reconciliation_result
    FOR EACH ROW EXECUTE FUNCTION reject_reconciliation_result_update();

CREATE OR REPLACE FUNCTION reject_reconciliation_result_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation_result rows are immutable: DELETE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reconciliation_result_no_delete
    BEFORE DELETE ON reconciliation_result
    FOR EACH ROW EXECUTE FUNCTION reject_reconciliation_result_delete();
