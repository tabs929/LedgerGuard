package com.tarun.ledgerguard.idempotency;

/**
 * Thrown when an Idempotency-Key is reused for a command that does not
 * canonically match the command the key was first claimed for — including
 * reuse across operation types (e.g. a key first used for a deposit,
 * replayed against the transfer endpoint). Mapped to 409 by
 * {@code common.GlobalExceptionHandler}.
 */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException(String idempotencyKey) {
		super("Idempotency-Key already used with a different request: " + idempotencyKey);
	}

}
