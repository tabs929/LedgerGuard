-- LedgerGuard Phase 2, Task 13: Kafka consumption and duplicate-event
-- protection.
--
-- One row per successfully processed Kafka ledger event, keyed by the
-- event's own stable eventId (Task 11/12's outbox_event.id) -- never by
-- Kafka topic/partition/offset, since the same event may legitimately be
-- redelivered at a different offset under Kafka's at-least-once delivery.
-- The (source_topic, source_partition, source_offset) uniqueness
-- constraint below is a corruption safeguard only: one Kafka position can
-- never represent two different successfully processed events. This
-- table has no foreign key to ledger_transaction or outbox_event -- the
-- consumer boundary must not require direct access to producer-side rows
-- to validate or process an event (see inbox.LedgerEventProcessor).
--
-- This migration adds durable deduplication only: no settlement, no
-- reconciliation, no balance/ledger mutation, no retry-count or
-- dead-letter tracking. See docs/ARCHITECTURE.md's "Kafka Consumption"
-- section.

CREATE TABLE processed_event (
    event_id           UUID PRIMARY KEY,
    aggregate_id        UUID NOT NULL,
    event_type          VARCHAR(30) NOT NULL,
    schema_version       INTEGER NOT NULL,

    -- SHA-256 hex digest of the exact UTF-8 Kafka value string, computed
    -- without deserializing/reserializing it. Distinguishes an identical
    -- redelivered duplicate (same hash) from a conflicting reuse of the
    -- same eventId (different hash) -- see inbox.LedgerEventProcessor.
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

    -- Corruption safeguard only -- see header comment. event_id remains
    -- the sole logical duplicate identity.
    CONSTRAINT uq_processed_event_source_position UNIQUE (source_topic, source_partition, source_offset)
);

-- =============================================================================
-- Append-only enforcement
-- =============================================================================
--
-- processed_event rows are never updated or deleted by application code
-- (inbox.ProcessedEventRepository exposes no update/delete operation).
-- These triggers back that with a database-level guarantee, in the same
-- style as V1's ledger triggers and V3's outbox triggers.

CREATE OR REPLACE FUNCTION reject_processed_event_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'processed_event rows are immutable: UPDATE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_processed_event_no_update
    BEFORE UPDATE ON processed_event
    FOR EACH ROW EXECUTE FUNCTION reject_processed_event_update();

CREATE OR REPLACE FUNCTION reject_processed_event_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'processed_event rows are immutable: DELETE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_processed_event_no_delete
    BEFORE DELETE ON processed_event
    FOR EACH ROW EXECUTE FUNCTION reject_processed_event_delete();
