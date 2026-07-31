package com.tarun.ledgerguard.outbox;

/**
 * The kind of domain object an {@link OutboxEvent} describes. Maps 1:1 to
 * {@code outbox_event.aggregate_type}'s CHECK constraint. Only
 * {@code LEDGER_TRANSACTION} exists in Task 11.
 */
public enum OutboxAggregateType {
	LEDGER_TRANSACTION
}
