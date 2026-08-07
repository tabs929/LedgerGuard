-- LedgerGuard Phase 3, Task 17: customer-account ownership and
-- principal-scoped idempotency, required by stateless JWT authentication
-- and ownership-based authorization.
--
-- =============================================================================
-- account.customer_subject
-- =============================================================================
--
-- The stable, JWT-`sub`-derived identifier of the CUSTOMER who owns a
-- CUSTOMER_WALLET account. Never populated from client-supplied JSON --
-- AccountService sources it exclusively from the validated JWT of the
-- authenticated principal at account-creation time. Must be NULL for every
-- SYSTEM account (there is no authenticated owner of EXTERNAL_FUNDING) and
-- NOT NULL for every CUSTOMER account, enforced below by
-- chk_account_ownership.
--
-- Existing CUSTOMER accounts (created before authentication existed) are
-- backfilled to one fixed, documented value -- 'legacy-unowned-customer' --
-- since no real authenticated identity was ever recorded for them. This is
-- an approved, explicit design decision, not a guess: there is no way to
-- recover true historical ownership, and a fixed sentinel makes the
-- backfill visible and searchable rather than silently absent.

ALTER TABLE account ADD COLUMN customer_subject VARCHAR(255);

UPDATE account
SET customer_subject = 'legacy-unowned-customer'
WHERE account_category = 'CUSTOMER';

ALTER TABLE account
    ADD CONSTRAINT chk_account_ownership CHECK (
        (account_category = 'CUSTOMER' AND customer_subject IS NOT NULL)
        OR (account_category = 'SYSTEM' AND customer_subject IS NULL)
    );

-- No index is added here. AccountService/AccountQueryService enforce
-- ownership by loading an account by its primary key and then comparing
-- the already-loaded row's customer_subject against the authenticated
-- principal -- a lookup that is already served by the primary key, not by
-- customer_subject. Task 17 introduces no "list accounts by owner" query,
-- so an index here would be speculative, justified only by a hypothetical
-- future API that is out of scope for this task.

-- =============================================================================
-- idempotency_key.principal_subject
-- =============================================================================
--
-- Idempotency-Key uniqueness was previously scoped to the raw key string
-- alone, globally, with no notion of which caller claimed it. Once
-- authentication exists, two different customers reusing the same literal
-- key string would collide: the second customer's request would either
-- replay the first customer's stored response (cross-customer leakage) or
-- 409-conflict against a key they never chose. This is a genuine
-- pre-existing gap that authentication makes exploitable, not just
-- defense-in-depth hardening -- see docs/ARCHITECTURE.md's "Idempotency"
-- section for the full reasoning.
--
-- Backfill is intentionally narrow: principal_subject is populated only by
-- joining to the already-backfilled customer_subject of the row's own
-- primary_account_id. No fallback sentinel is used here. If any existing
-- idempotency_key row's primary_account_id does not resolve to a
-- CUSTOMER account with a customer_subject (e.g. it points at a SYSTEM
-- account, or a row the current schema should never have produced), the
-- subsequent SET NOT NULL below fails the migration outright rather than
-- silently mapping a possibly-corrupted row to a shared legacy principal,
-- which could otherwise conceal a real data-integrity defect.

ALTER TABLE idempotency_key ADD COLUMN principal_subject VARCHAR(255);

UPDATE idempotency_key ik
SET principal_subject = a.customer_subject
FROM account a
WHERE a.id = ik.primary_account_id;

ALTER TABLE idempotency_key ALTER COLUMN principal_subject SET NOT NULL;

ALTER TABLE idempotency_key DROP CONSTRAINT uq_idempotency_key;
ALTER TABLE idempotency_key
    ADD CONSTRAINT uq_idempotency_key_principal UNIQUE (principal_subject, idempotency_key);
