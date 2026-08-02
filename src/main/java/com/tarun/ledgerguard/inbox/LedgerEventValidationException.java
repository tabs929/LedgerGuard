package com.tarun.ledgerguard.inbox;

/**
 * Thrown for any structurally or semantically invalid Kafka ledger event:
 * malformed JSON, a non-object payload, a missing/unexpected field, an
 * invalid UUID/timestamp/amount/currency, an unsupported event type or
 * schema version, or a Kafka key that doesn't match the payload's
 * {@code transactionId}. The message is always a safe, generic
 * description of *which* rule failed — never the payload content itself
 * (see {@code inbox.LedgerEventConsumer} for the logging rule this
 * supports).
 */
public class LedgerEventValidationException extends RuntimeException {

	public LedgerEventValidationException(String message) {
		super(message);
	}

}
