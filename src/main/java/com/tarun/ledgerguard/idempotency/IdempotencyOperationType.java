package com.tarun.ledgerguard.idempotency;

/**
 * The two write operations Task 10 protects. Maps 1:1 to
 * {@code idempotency_key.operation_type}'s CHECK constraint. Reusing the
 * same key for a different operation type (e.g. a deposit key replayed
 * against the transfer endpoint) is a conflict, never a replay — see
 * {@link IdempotencyCommand#matches(IdempotencyCommand)}.
 */
public enum IdempotencyOperationType {
	DEPOSIT,
	TRANSFER
}
