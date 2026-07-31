package com.tarun.ledgerguard.outbox;

/**
 * Maps 1:1 to {@code outbox_event.event_type}'s CHECK constraint. One
 * event type per completed financial operation Task 11 covers.
 */
public enum OutboxEventType {
	DEPOSIT_COMPLETED,
	TRANSFER_COMPLETED
}
