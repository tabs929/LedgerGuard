-- LedgerGuard Phase 2, Task 14: settlement CSV import.
--
-- Records immutable external settlement observations reported by a bank or
-- payment processor via CSV upload. This migration adds persistence only:
-- no reconciliation, no ledger/balance mutation, no outbox event, no Kafka
-- publishing, no status/match-result columns. Reconciling these
-- observations against LedgerGuard's own ledger is Task 15's job, not
-- this one's -- see docs/ARCHITECTURE.md's "Settlement Import" section.
--
-- Two tables:
--   * settlement_import  -- one row per successfully committed import
--     (whole-file audit record: source, exact file identity, row counts).
--   * settlement_record  -- one row per distinct settlement observation
--     (source, external_reference identity), referencing the import that
--     first created it.
--
-- Both tables are append-only, in the same style as V1's ledger triggers,
-- V3's outbox triggers, and V4's processed_event triggers: BEFORE UPDATE
-- OR DELETE triggers unconditionally reject mutation. No application code
-- path exposes an update or delete operation on either table.

-- =============================================================================
-- settlement_import
-- =============================================================================
--
-- One row per committed whole-file import. Because this table is
-- append-only, its row counts (total/inserted/duplicate) are computed
-- entirely in memory during CSV parsing and duplicate/conflict
-- classification, then inserted once as part of the same transaction that
-- inserts the settlement_record rows it produced -- never inserted first
-- and updated afterward.
--
-- Logical file identity is (normalized_source, file_hash), enforced by the
-- unique constraint below. file_hash is a SHA-256 hex digest computed over
-- the exact uploaded bytes, before any BOM removal, decoding, or CSV
-- parsing -- see settlement.SettlementImportService. Re-uploading the
-- exact same bytes from the exact same normalized source is a safe,
-- idempotent replay (see settlement.SettlementImportProcessor), not a
-- duplicate row.

CREATE TABLE settlement_import (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Display value as submitted by the caller (trimmed, not case-folded).
    source                 VARCHAR(64) NOT NULL,
    -- Lowercase, trimmed normalization of source, used for identity and
    -- for settlement_record's (normalized_source, external_reference)
    -- identity. See settlement.SourceNormalizer.
    normalized_source      VARCHAR(64) NOT NULL,

    -- Audit metadata only -- the sanitized basename of the submitted
    -- filename, never used for identity, parsing, authorization, or
    -- filesystem paths. Nullable: a multipart part need not carry a
    -- filename.
    original_filename      VARCHAR(255),

    -- SHA-256 hex digest of the exact uploaded file bytes (before any
    -- decoding/normalization). Two byte-distinct files with logically
    -- equivalent rows have different file_hash values by design.
    file_hash               CHAR(64) NOT NULL,
    file_size_bytes          BIGINT NOT NULL,

    total_row_count           INTEGER NOT NULL,
    inserted_row_count        INTEGER NOT NULL,
    duplicate_row_count       INTEGER NOT NULL,

    imported_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_settlement_import_source_nonblank CHECK (btrim(source) <> ''),
    CONSTRAINT chk_settlement_import_normalized_source_nonblank CHECK (btrim(normalized_source) <> ''),
    CONSTRAINT chk_settlement_import_original_filename_nonblank
        CHECK (original_filename IS NULL OR btrim(original_filename) <> ''),
    CONSTRAINT chk_settlement_import_file_hash_format CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    -- Database-level sanity bound, independent of the application's
    -- configurable (and smaller) default max-file-size property -- this
    -- is a defensive outer ceiling, not the enforced limit itself. See
    -- settlement.SettlementImportProperties for the actual configured
    -- limit.
    CONSTRAINT chk_settlement_import_file_size_bounds CHECK (file_size_bytes > 0 AND file_size_bytes <= 104857600),
    CONSTRAINT chk_settlement_import_total_row_count_positive CHECK (total_row_count > 0),
    CONSTRAINT chk_settlement_import_inserted_row_count_nonneg CHECK (inserted_row_count >= 0),
    CONSTRAINT chk_settlement_import_duplicate_row_count_nonneg CHECK (duplicate_row_count >= 0),
    -- Every row in the file is accounted for exactly once: either newly
    -- inserted or an identical duplicate of an already-persisted row.
    -- (A conflicting row instead aborts the whole import -- see
    -- settlement.SettlementImportProcessor -- so no row is ever counted
    -- as "conflicting" here.)
    CONSTRAINT chk_settlement_import_row_counts_consistent
        CHECK (inserted_row_count + duplicate_row_count = total_row_count),

    -- The logical identity of a committed import. A second upload of the
    -- exact same bytes from the exact same normalized source is rejected
    -- here at the database level if the application's own atomic claim
    -- (INSERT ... ON CONFLICT DO NOTHING, see
    -- settlement.SettlementImportRepository) is ever bypassed.
    CONSTRAINT uq_settlement_import_source_file UNIQUE (normalized_source, file_hash)
);

-- =============================================================================
-- settlement_record
-- =============================================================================
--
-- One row per distinct settlement observation, identified by
-- (normalized_source, external_reference). transaction_id is the
-- LedgerGuard transaction UUID the external source reports -- it is
-- accepted and stored as-is, with NO foreign key to ledger_transaction.
-- An unmatched/unknown transaction id is expected, valid evidence for
-- future reconciliation (Task 15), not an error; adding a foreign key
-- would incorrectly reject exactly the rows this table exists to retain.
--
-- row_hash is the SHA-256 hex digest of the canonical, unambiguous
-- (length-prefixed) encoding of every business field below -- see
-- settlement.RowFingerprint. It is what identical-vs-conflicting
-- duplicate classification compares, alongside the individual business
-- columns.

CREATE TABLE settlement_record (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    normalized_source     VARCHAR(64) NOT NULL,
    external_reference    VARCHAR(128) NOT NULL,

    -- The external source's reported LedgerGuard transaction id.
    -- Deliberately NOT a foreign key to ledger_transaction(id) -- see
    -- header comment and docs/ARCHITECTURE.md's "Settlement Import"
    -- section.
    transaction_id         UUID NOT NULL,

    amount                  NUMERIC(19,2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL,
    settled_at                TIMESTAMPTZ NOT NULL,

    row_hash                  CHAR(64) NOT NULL,

    -- The import that first created this observation. Later files
    -- containing an identical row do not add a row here or a new mapping
    -- row anywhere -- they are represented only through the duplicate
    -- counts on their own settlement_import row (see V5 header comment
    -- and docs/ARCHITECTURE.md). A many-to-many observation/import
    -- mapping is explicitly out of scope for Task 14.
    first_import_id            UUID NOT NULL,
    source_row_number          INTEGER NOT NULL,

    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_settlement_record_external_reference_nonblank CHECK (btrim(external_reference) <> ''),
    CONSTRAINT chk_settlement_record_normalized_source_nonblank CHECK (btrim(normalized_source) <> ''),
    CONSTRAINT chk_settlement_record_amount_positive CHECK (amount > 0),
    -- Currency format only (three uppercase ASCII letters) -- the same
    -- convention as account.currency and ledger_entry.currency in V1.
    -- Whether a given currency is one LedgerGuard actually supports is an
    -- application-level check (settlement.SettlementCsvParser), not a
    -- database constraint, matching how account/ledger_entry already
    -- separate currency *format* (database) from currency *support*
    -- (application) -- see docs/DATA_MODEL.md.
    CONSTRAINT chk_settlement_record_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_settlement_record_row_hash_format CHECK (row_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_settlement_record_source_row_number_positive CHECK (source_row_number > 0),

    -- DEFERRABLE INITIALLY DEFERRED (checked at COMMIT, not per-statement):
    -- settlement.SettlementImportProcessor claims settlement_record rows
    -- for a new import BEFORE inserting that import's own
    -- settlement_import row (it must finish counting inserted/duplicate
    -- rows first, since settlement_import is append-only and is never
    -- inserted with placeholder counts and updated afterward -- see the
    -- header comment above). A same-statement-checked (default) foreign
    -- key would reject that ordering outright.
    CONSTRAINT fk_settlement_record_first_import FOREIGN KEY (first_import_id)
        REFERENCES settlement_import(id) DEFERRABLE INITIALLY DEFERRED,

    -- The logical identity of a settlement observation. The same external
    -- reference from two different sources is two different observations
    -- (normalized_source is part of the key); the same external reference
    -- reused within one source, with different business fields, is a
    -- conflict rejected by the application before this constraint would
    -- ever be reached in practice -- see
    -- settlement.SettlementImportProcessor.
    CONSTRAINT uq_settlement_record_source_reference UNIQUE (normalized_source, external_reference)
);

-- Supports "which observations were first created by import X" (audit /
-- future Task 15 lookups) and keeps the foreign key indexed (Postgres does
-- not index FK columns automatically).
CREATE INDEX idx_settlement_record_first_import_id ON settlement_record (first_import_id);

-- Supports the primary future Task 15 reconciliation query: "which
-- settlement observations, if any, reference this ledger transaction".
CREATE INDEX idx_settlement_record_transaction_id ON settlement_record (transaction_id);

-- =============================================================================
-- Append-only enforcement
-- =============================================================================

CREATE OR REPLACE FUNCTION reject_settlement_import_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'settlement_import rows are immutable: UPDATE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_import_no_update
    BEFORE UPDATE ON settlement_import
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_import_update();

CREATE OR REPLACE FUNCTION reject_settlement_import_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'settlement_import rows are immutable: DELETE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_import_no_delete
    BEFORE DELETE ON settlement_import
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_import_delete();

CREATE OR REPLACE FUNCTION reject_settlement_record_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'settlement_record rows are immutable: UPDATE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_record_no_update
    BEFORE UPDATE ON settlement_record
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_record_update();

CREATE OR REPLACE FUNCTION reject_settlement_record_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'settlement_record rows are immutable: DELETE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_record_no_delete
    BEFORE DELETE ON settlement_record
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_record_delete();
