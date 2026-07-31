package com.tarun.ledgerguard.outbox;

import java.util.UUID;

/**
 * The version-1 {@code DEPOSIT_COMPLETED} event payload — exactly the
 * fields in the approved Task 11 contract, no more. {@code amount} is a
 * pre-formatted decimal string (never a bare JSON number) and
 * {@code occurredAt} is a pre-formatted ISO-8601 UTC string, both built by
 * {@link OutboxEventFactory} rather than left to Jackson's default
 * {@code BigDecimal}/{@code Instant} serialization, so the wire format is
 * deterministic and locale-independent regardless of Jackson
 * configuration. Deliberately excludes the internal
 * SYSTEM/EXTERNAL_FUNDING account id, the raw Idempotency-Key, and every
 * other field {@code docs/API_SPEC.md}'s Idempotency section already
 * forbids from being disclosed.
 */
public record DepositCompletedEvent(
		UUID eventId,
		String eventType,
		int schemaVersion,
		String occurredAt,
		UUID transactionId,
		UUID destinationAccountId,
		String amount,
		String currency) {
}
