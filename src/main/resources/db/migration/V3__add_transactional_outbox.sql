-- LedgerGuard Phase 2, Task 11: transactional outbox for deposits and transfers.
--
-- One row per completed deposit or transfer ledger transaction. Written in
-- the same PostgreSQL transaction as ledger_transaction, ledger_entry, the
-- account balance updates, and the Task 10 idempotency_key row -- all or
-- nothing together (see outbox.OutboxEventFactory and
-- docs/ARCHITECTURE.md's "Transactional Outbox" section). This migration
-- adds persistence only: no publisher, no consumer, no Kafka. published_at
-- is reserved for a later task's publisher to claim rows with.

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

    -- At most one event of a given type per ledger transaction -- this is
    -- what makes a Task 10 replay (which never reaches this insert at all)
    -- structurally unable to create a duplicate even if it somehow did.
    CONSTRAINT uq_outbox_event_identity UNIQUE (aggregate_type, aggregate_id, event_type)
);

-- Supports the future publisher's poll: pending rows only, in a
-- deterministic (created_at, id) order. No attempt counts, retry
-- timestamps, Kafka offsets, or broker metadata -- those belong to the
-- task that actually publishes.
CREATE INDEX idx_outbox_event_pending ON outbox_event (created_at, id)
    WHERE published_at IS NULL;

-- =============================================================================
-- Event immutability
-- =============================================================================
--
-- The business identity and content of a stored event never changes once
-- written. DELETE is rejected outright. UPDATE is rejected unless it is
-- exactly the one permitted transition a future publisher needs:
-- published_at moving from NULL to a non-null value, with every other
-- column (including published_at itself, once set) unchanged.

CREATE OR REPLACE FUNCTION reject_outbox_event_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'outbox_event rows are immutable: DELETE not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_event_no_delete
    BEFORE DELETE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION reject_outbox_event_delete();

CREATE OR REPLACE FUNCTION reject_outbox_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION 'outbox_event business fields are immutable: UPDATE not permitted';
    END IF;

    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'outbox_event.published_at cannot be cleared or overwritten once set';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_event_immutable
    BEFORE UPDATE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION reject_outbox_event_mutation();
