package com.tarun.ledgerguard.inbox;

import java.util.UUID;

/**
 * Thrown when a Kafka record's {@code eventId} already has a
 * {@code processed_event} row, but that row's aggregate id, event type,
 * schema version, or payload fingerprint does not match this delivery —
 * i.e. the same event id was reused for genuinely different content. This
 * is never expected under correct producer behavior (Task 11's
 * {@code uq_outbox_event_identity} and stable {@code eventId} generation
 * make it structurally very unlikely), but must not be silently accepted
 * as a successful duplicate. The existing row is never mutated; the
 * record is never acknowledged as successfully processed.
 */
public class ConflictingEventException extends RuntimeException {

	public ConflictingEventException(UUID eventId) {
		super("processed_event already exists for eventId " + eventId + " with different content");
	}

}
